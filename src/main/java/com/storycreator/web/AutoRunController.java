package com.storycreator.web;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.workflow.autorun.AutoRunService;
import com.storycreator.workflow.autorun.AutoRunService.AutoRunObservation;
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
@RequestMapping("/projects/{projectId}/auto-run")
public class AutoRunController {

    private static final Logger log = LoggerFactory.getLogger(AutoRunController.class);

    private final AutoRunService autoRunService;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AutoRunController(AutoRunService autoRunService,
                            AutoRunStepConfigRepository autoRunStepConfigRepository) {
        this.autoRunService = autoRunService;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@PathVariable Long projectId) {
        try {
            autoRunService.startAutoRun(projectId);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable Long projectId) {
        autoRunService.stopAutoRun(projectId);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long projectId) {
        return ResponseEntity.ok(autoRunService.getStatus(projectId));
    }

    @PutMapping("/step-config")
    public ResponseEntity<Map<String, Object>> updateStepConfig(@PathVariable Long projectId,
                                                                 @RequestParam String step,
                                                                 @RequestParam boolean enabled) {
        WorkflowStep ws = WorkflowStep.valueOf(step);
        AutoRunStepConfigEntity config = autoRunStepConfigRepository.findByProjectIdAndStep(projectId, ws)
                .orElseGet(() -> new AutoRunStepConfigEntity(projectId, ws, enabled));
        config.setEnabled(enabled);
        autoRunStepConfigRepository.save(config);
        return ResponseEntity.ok(Map.of("status", "ok", "step", step, "enabled", enabled));
    }

    @GetMapping("/stream-status")
    public ResponseEntity<Map<String, Object>> streamStatus(@PathVariable Long projectId) {
        AutoRunObservation obs = autoRunService.getObservation(projectId);
        if (obs == null || !obs.isActive()) {
            return ResponseEntity.ok(Map.of("active", false, "step", "", "chapter", 0));
        }
        return ResponseEntity.ok(Map.of(
                "active", true,
                "step", obs.getCurrentStepName(),
                "chapter", obs.getCurrentChapter()
        ));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long projectId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        AutoRunObservation obs = autoRunService.getObservation(projectId);
        if (obs == null) {
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data("没有活跃的自动运行任务"));
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
                // Send current step info
                emitter.send(SseEmitter.event().name("step-info").data(
                        obs.getCurrentStepName() + "|" + obs.getCurrentChapter()));

                // Send replay buffer (tokens accumulated for current sub-step)
                String replay = obs.getTokenBuffer();
                if (replay != null && !replay.isEmpty()) {
                    emitter.send(SseEmitter.event().name("replay-buffer").data(replay));
                }

                if (!obs.isActive()) {
                    emitter.send(SseEmitter.event().name("done").data("complete"));
                    emitter.complete();
                    return;
                }

                // Subscribe to live token stream
                subscription = obs.getSink().asFlux()
                        .doOnNext(token -> {
                            try {
                                if (token.startsWith("[[AUTORUN_STEP:")) {
                                    // Parse step change: [[AUTORUN_STEP:STEP_NAME:chapter]]
                                    String payload = token.substring(15, token.length() - 2);
                                    emitter.send(SseEmitter.event().name("step-info").data(payload));
                                } else {
                                    emitter.send(SseEmitter.event().name("token").data(token));
                                }
                            } catch (IOException e) {
                                // Client disconnected
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("complete"));
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

        emitter.onTimeout(() -> log.info("[P{}] auto-run stream timeout", projectId));
        emitter.onCompletion(() -> log.debug("[P{}] auto-run stream completed", projectId));

        return emitter;
    }
}
