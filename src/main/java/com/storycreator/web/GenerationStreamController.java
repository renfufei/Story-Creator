package com.storycreator.web;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.repository.WorkflowStateRepository;
import com.storycreator.workflow.background.BackgroundGenerationService;
import com.storycreator.workflow.background.BackgroundGenerationService.GenerationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/projects/{projectId}/bg-gen")
public class GenerationStreamController {

    private static final Logger log = LoggerFactory.getLogger(GenerationStreamController.class);

    private final BackgroundGenerationService bgService;
    private final WorkflowStateRepository workflowStateRepository;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GenerationStreamController(BackgroundGenerationService bgService,
                                       WorkflowStateRepository workflowStateRepository) {
        this.bgService = bgService;
        this.workflowStateRepository = workflowStateRepository;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@PathVariable Long projectId,
                                                      @RequestParam WorkflowStep step,
                                                      @RequestParam(defaultValue = "0") int chapter) {
        try {
            bgService.startGeneration(projectId, step, chapter);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.error("[P{}] bg-gen start failed step={}: {}", projectId, step, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable Long projectId,
                                                     @RequestParam WorkflowStep step,
                                                     @RequestParam(defaultValue = "0") int chapter) {
        bgService.stopGeneration(projectId, step, chapter);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long projectId,
                                                       @RequestParam WorkflowStep step,
                                                       @RequestParam(defaultValue = "0") int chapter) {
        boolean bgActive = bgService.isActive(projectId, step, chapter);
        String dbStatus = workflowStateRepository.findByProjectIdAndStep(projectId, step)
                .map(s -> s.getStatus().name())
                .orElse("NOT_STARTED");
        return ResponseEntity.ok(Map.of(
                "bgActive", bgActive,
                "status", dbStatus
        ));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long projectId,
                             @RequestParam WorkflowStep step,
                             @RequestParam(defaultValue = "0") int chapter) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 min timeout

        GenerationTask task = bgService.getActiveTask(projectId, step, chapter);
        if (task == null) {
            // No active task, complete immediately
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data("没有活跃的后台任务"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }

        executor.submit(() -> {
            Disposable subscription = null;
            try {
                // Send replay buffer first (content accumulated so far)
                String replay = task.getContentBuffer();
                if (replay != null && !replay.isEmpty()) {
                    emitter.send(SseEmitter.event().name("replay-buffer").data(replay));
                }

                // If task already completed, send done and return
                if (task.isCompleted()) {
                    emitter.send(SseEmitter.event().name("done").data("complete"));
                    emitter.complete();
                    return;
                }
                if (task.isErrored()) {
                    emitter.send(SseEmitter.event().name("error").data(task.getErrorMessage()));
                    emitter.complete();
                    return;
                }

                // Subscribe to live token stream
                final boolean[] done = {false};
                subscription = task.getSink().asFlux()
                        .doOnNext(token -> {
                            try {
                                if (token.startsWith("[[CHAR:")) {
                                    emitter.send(SseEmitter.event().name("char-section").data(token));
                                } else if (token.startsWith("[[SECTION:")) {
                                    emitter.send(SseEmitter.event().name("outline-section").data(token));
                                } else if (token.startsWith("[[PROOFREAD:")) {
                                    emitter.send(SseEmitter.event().name("proofread-section").data(token));
                                } else if (token.equals("[[BG_STOPPED]]")) {
                                    emitter.send(SseEmitter.event().name("stopped").data("stopped"));
                                } else if (token.startsWith("[[BG_ERROR:")) {
                                    String msg = token.substring(11, token.length() - 2);
                                    emitter.send(SseEmitter.event().name("error").data(msg));
                                } else {
                                    emitter.send(SseEmitter.event().name("token").data(token));
                                }
                            } catch (IOException e) {
                                // Client disconnected, will be handled by onCompletion
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                done[0] = true;
                                if (task.isCompleted()) {
                                    emitter.send(SseEmitter.event().name("done").data("complete"));
                                }
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .subscribe();

                // Block until subscription is disposed (stream ended)
                while (!subscription.isDisposed()) {
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                if (subscription != null && !subscription.isDisposed()) {
                    subscription.dispose();
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });

        // On timeout/disconnect: only cancel subscription, do NOT reset generating status
        emitter.onTimeout(() -> log.info("[P{}] bg-gen stream timeout step={}", projectId, step));
        emitter.onCompletion(() -> log.debug("[P{}] bg-gen stream completed step={}", projectId, step));

        return emitter;
    }
}
