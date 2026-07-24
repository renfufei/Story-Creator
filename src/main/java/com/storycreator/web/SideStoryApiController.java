package com.storycreator.web;

import com.storycreator.persistence.entity.SideStoryChapterEntity;
import com.storycreator.persistence.entity.SideStoryEntity;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.sidestory.SideStoryAutoRunService;
import com.storycreator.sidestory.SideStoryBackgroundService;
import com.storycreator.sidestory.SideStoryBackgroundService.GenerationTask;
import com.storycreator.sidestory.SideStoryWorkflowService;
import com.storycreator.workflow.autorun.AutoRunObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/projects/{projectId}/side-stories/api")
public class SideStoryApiController {

    private static final Logger log = LoggerFactory.getLogger(SideStoryApiController.class);

    private final SideStoryRepository sideStoryRepository;
    private final SideStoryChapterRepository sideStoryChapterRepository;
    private final SideStoryWorkflowService workflowService;
    private final SideStoryBackgroundService bgService;
    private final SideStoryAutoRunService autoRunService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SideStoryApiController(SideStoryRepository sideStoryRepository,
                                   SideStoryChapterRepository sideStoryChapterRepository,
                                   SideStoryWorkflowService workflowService,
                                   SideStoryBackgroundService bgService,
                                   SideStoryAutoRunService autoRunService) {
        this.sideStoryRepository = sideStoryRepository;
        this.sideStoryChapterRepository = sideStoryChapterRepository;
        this.workflowService = workflowService;
        this.bgService = bgService;
        this.autoRunService = autoRunService;
    }

    // ==================== CRUD ====================

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable Long projectId,
                                                       @RequestBody Map<String, Object> body) {
        SideStoryEntity entity = new SideStoryEntity();
        entity.setProjectId(projectId);
        entity.setTitle((String) body.get("title"));
        entity.setDescription((String) body.get("description"));
        entity.setType((String) body.getOrDefault("type", "SUPPLEMENTARY"));
        entity.setCreativeGuidance((String) body.get("creativeGuidance"));
        if (body.get("attachedVolume") != null) {
            entity.setAttachedVolume(((Number) body.get("attachedVolume")).intValue());
        }
        int maxOrder = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .mapToInt(SideStoryEntity::getSortOrder).max().orElse(0);
        entity.setSortOrder(maxOrder + 1);

        entity = sideStoryRepository.save(entity);

        // Set character associations
        @SuppressWarnings("unchecked")
        List<Number> characterIds = (List<Number>) body.get("characterIds");
        if (characterIds != null) {
            workflowService.setCharacterIds(entity.getId(), characterIds.stream()
                    .map(Number::longValue).toList());
        }

        return ResponseEntity.ok(Map.of("id", entity.getId(), "status", "ok"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, String>> update(@PathVariable Long projectId,
                                                       @PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        SideStoryEntity entity = sideStoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + id));

        if (body.containsKey("title")) entity.setTitle((String) body.get("title"));
        if (body.containsKey("description")) entity.setDescription((String) body.get("description"));
        if (body.containsKey("type")) entity.setType((String) body.get("type"));
        if (body.containsKey("creativeGuidance")) entity.setCreativeGuidance((String) body.get("creativeGuidance"));
        if (body.containsKey("attachedVolume")) {
            entity.setAttachedVolume(body.get("attachedVolume") != null ?
                    ((Number) body.get("attachedVolume")).intValue() : null);
        }
        if (body.containsKey("outline")) entity.setOutline((String) body.get("outline"));
        if (body.containsKey("arcName")) entity.setArcName((String) body.get("arcName"));
        if (body.containsKey("status")) entity.setStatus((String) body.get("status"));

        sideStoryRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long projectId, @PathVariable Long id) {
        sideStoryChapterRepository.deleteBySideStoryId(id);
        workflowService.setCharacterIds(id, List.of());
        sideStoryRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long projectId, @PathVariable Long id) {
        SideStoryEntity entity = sideStoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + id));
        var chapters = sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(id);
        var characterIds = workflowService.getCharacterIds(id);
        return ResponseEntity.ok(Map.of(
                "sideStory", entity,
                "chapters", chapters,
                "characterIds", characterIds
        ));
    }

    // ==================== Chapter Management ====================

    @PostMapping("/{id}/chapters")
    public ResponseEntity<Map<String, String>> createChapters(@PathVariable Long projectId,
                                                               @PathVariable Long id,
                                                               @RequestBody Map<String, Object> body) {
        int count = ((Number) body.get("count")).intValue();
        int existing = sideStoryChapterRepository.countBySideStoryId(id);

        for (int i = 1; i <= count; i++) {
            SideStoryChapterEntity ch = new SideStoryChapterEntity();
            ch.setSideStoryId(id);
            ch.setProjectId(projectId);
            ch.setChapterNumber(existing + i);
            sideStoryChapterRepository.save(ch);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @DeleteMapping("/{id}/chapters/{chapterNumber}")
    public ResponseEntity<Map<String, String>> deleteChapter(@PathVariable Long projectId,
                                                              @PathVariable Long id,
                                                              @PathVariable int chapterNumber) {
        sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(id, chapterNumber)
                .ifPresent(sideStoryChapterRepository::delete);
        // Renumber remaining chapters
        var remaining = sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(id);
        int num = 1;
        for (var ch : remaining) {
            if (ch.getChapterNumber() != num) {
                ch.setChapterNumber(num);
                sideStoryChapterRepository.save(ch);
            }
            num++;
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PutMapping("/{id}/chapters/{chapterNumber}")
    public ResponseEntity<Map<String, String>> updateChapter(@PathVariable Long projectId,
                                                              @PathVariable Long id,
                                                              @PathVariable int chapterNumber,
                                                              @RequestBody Map<String, Object> body) {
        SideStoryChapterEntity ch = sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(id, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        if (body.containsKey("title")) ch.setTitle((String) body.get("title"));
        if (body.containsKey("outlineSummary")) ch.setOutlineSummary((String) body.get("outlineSummary"));
        if (body.containsKey("content")) {
            String content = (String) body.get("content");
            ch.setContent(content);
            ch.setWordCount(content != null ? content.length() : 0);
        }
        if (body.containsKey("status")) ch.setStatus((String) body.get("status"));
        sideStoryChapterRepository.save(ch);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ==================== Character Association ====================

    @GetMapping("/{id}/characters")
    public ResponseEntity<List<Long>> getCharacters(@PathVariable Long projectId, @PathVariable Long id) {
        return ResponseEntity.ok(workflowService.getCharacterIds(id));
    }

    @PostMapping("/{id}/characters")
    public ResponseEntity<Map<String, String>> setCharacters(@PathVariable Long projectId,
                                                              @PathVariable Long id,
                                                              @RequestBody List<Long> characterIds) {
        workflowService.setCharacterIds(id, characterIds);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ==================== Generation Endpoints ====================

    @PostMapping("/{id}/generate/outline")
    public ResponseEntity<Map<String, String>> startOutlineGeneration(@PathVariable Long projectId,
                                                                       @PathVariable Long id) {
        try {
            Flux<String> flux = workflowService.generateOutline(projectId, id);
            bgService.startGeneration(id, "OUTLINE", 0, flux,
                    () -> workflowService.saveOutline(id, bgService.getActiveTask(id, "OUTLINE", 0).getFullContent()),
                    () -> {}
            );
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "message", SseErrorHelper.sanitize(e)));
        }
    }

    @PostMapping("/{id}/generate/chapter-outline/{chapterNumber}")
    public ResponseEntity<Map<String, String>> startChapterOutlineGeneration(@PathVariable Long projectId,
                                                                              @PathVariable Long id,
                                                                              @PathVariable int chapterNumber) {
        try {
            Flux<String> flux = workflowService.generateChapterOutline(projectId, id, chapterNumber);
            bgService.startGeneration(id, "CHAPTER_OUTLINE", chapterNumber, flux,
                    () -> workflowService.saveChapterOutline(id, chapterNumber,
                            bgService.getActiveTask(id, "CHAPTER_OUTLINE", chapterNumber).getFullContent()),
                    () -> {}
            );
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "message", SseErrorHelper.sanitize(e)));
        }
    }

    @PostMapping("/{id}/generate/writing/{chapterNumber}")
    public ResponseEntity<Map<String, String>> startWriting(@PathVariable Long projectId,
                                                             @PathVariable Long id,
                                                             @PathVariable int chapterNumber) {
        try {
            Flux<String> flux = workflowService.generateChapterContent(projectId, id, chapterNumber);
            bgService.startGeneration(id, "WRITING", chapterNumber, flux,
                    () -> workflowService.saveChapterContent(id, chapterNumber,
                            bgService.getActiveTask(id, "WRITING", chapterNumber).getFullContent()),
                    () -> workflowService.resetChapterStatus(id, chapterNumber, "NOT_STARTED")
            );
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "message", SseErrorHelper.sanitize(e)));
        }
    }

    @PostMapping("/{id}/generate/polish/{chapterNumber}")
    public ResponseEntity<Map<String, String>> startPolish(@PathVariable Long projectId,
                                                            @PathVariable Long id,
                                                            @PathVariable int chapterNumber) {
        try {
            Flux<String> flux = workflowService.polishChapter(projectId, id, chapterNumber);
            bgService.startGeneration(id, "POLISH", chapterNumber, flux,
                    () -> workflowService.savePolishedContent(id, chapterNumber,
                            bgService.getActiveTask(id, "POLISH", chapterNumber).getFullContent()),
                    () -> workflowService.resetChapterStatus(id, chapterNumber, "COMPLETED")
            );
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "message", SseErrorHelper.sanitize(e)));
        }
    }

    @PostMapping("/{id}/generate/stop")
    public ResponseEntity<Map<String, String>> stopGeneration(@PathVariable Long projectId,
                                                               @PathVariable Long id,
                                                               @RequestParam String operation,
                                                               @RequestParam(defaultValue = "0") int chapterNumber) {
        bgService.stopGeneration(id, operation, chapterNumber);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping(value = "/{id}/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long projectId,
                             @PathVariable Long id,
                             @RequestParam String operation,
                             @RequestParam(defaultValue = "0") int chapterNumber) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        GenerationTask task = bgService.getActiveTask(id, operation, chapterNumber);
        if (task == null) {
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
                String replay = task.getContentBuffer();
                if (replay != null && !replay.isEmpty()) {
                    emitter.send(SseEmitter.event().name("replay-buffer").data(replay));
                }

                if (task.isCompleted()) {
                    emitter.send(SseEmitter.event().name("done").data("complete"));
                    emitter.complete();
                    return;
                }
                if (task.isErrored()) {
                    emitter.send(SseEmitter.event().name("error").data(
                            SseErrorHelper.sanitize(new RuntimeException(task.getErrorMessage()))));
                    emitter.complete();
                    return;
                }

                subscription = task.getSink().asFlux()
                        .doOnNext(token -> {
                            try {
                                if (token.equals("[[BG_STOPPED]]")) {
                                    emitter.send(SseEmitter.event().name("stopped").data("stopped"));
                                } else if (token.startsWith("[[BG_ERROR:")) {
                                    String msg = token.substring(11, token.length() - 2);
                                    emitter.send(SseEmitter.event().name("error").data(msg));
                                } else {
                                    emitter.send(SseEmitter.event().name("token").data(token));
                                }
                            } catch (IOException e) {
                                // Client disconnected
                            }
                        })
                        .doOnComplete(() -> {
                            try {
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
                                emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(error)));
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
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });

        emitter.onTimeout(() -> log.debug("Side story stream timeout sideStory={}", id));
        emitter.onCompletion(() -> log.debug("Side story stream completed sideStory={}", id));

        return emitter;
    }

    // ==================== Auto Run Endpoints ====================

    @PostMapping("/{id}/auto-run/start")
    public ResponseEntity<Map<String, String>> startAutoRun(@PathVariable Long projectId, @PathVariable Long id) {
        try {
            autoRunService.startAutoRun(projectId, id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", SseErrorHelper.sanitize(e)));
        }
    }

    @PostMapping("/{id}/auto-run/stop")
    public ResponseEntity<Map<String, String>> stopAutoRun(@PathVariable Long projectId, @PathVariable Long id) {
        autoRunService.stopAutoRun(id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/{id}/auto-run/status")
    public ResponseEntity<Map<String, Object>> autoRunStatus(@PathVariable Long projectId, @PathVariable Long id) {
        return ResponseEntity.ok(autoRunService.getStatus(id));
    }

    @GetMapping(value = "/{id}/auto-run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter autoRunStream(@PathVariable Long projectId, @PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        AutoRunObservation obs = autoRunService.getObservation(id);
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
                emitter.send(SseEmitter.event().name("step-info").data(
                        obs.getCurrentStepName() + "|" + obs.getCurrentChapter()));

                AutoRunObservation.ReadResult initial = obs.readFrom(0);
                final long[] batchHolder = {initial.batchId()};
                final int[] posHolder = {initial.endIndex()};

                if (!initial.content().isEmpty()) {
                    emitter.send(SseEmitter.event().name("replay-buffer").data(initial.content()));
                }

                if (!obs.isActive()) {
                    emitter.send(SseEmitter.event().name("done").data("complete"));
                    emitter.complete();
                    return;
                }

                subscription = obs.getNotifySink().asFlux()
                        .doOnNext(signal -> {
                            try {
                                long newBatch = obs.getBatchId();
                                if (newBatch != batchHolder[0]) {
                                    batchHolder[0] = newBatch;
                                    posHolder[0] = 0;
                                    emitter.send(SseEmitter.event().name("step-info").data(
                                            obs.getCurrentStepName() + "|" + obs.getCurrentChapter()));
                                }
                                AutoRunObservation.ReadResult result = obs.readFrom(posHolder[0]);
                                if (result.batchId() == batchHolder[0] && !result.content().isEmpty()) {
                                    posHolder[0] = result.endIndex();
                                    emitter.send(SseEmitter.event().name("token").data(result.content()));
                                }
                            } catch (IOException e) {
                                // client disconnected
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
                                emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(error)));
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
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });

        emitter.onTimeout(() -> log.debug("Side story auto-run stream timeout sideStory={}", id));
        emitter.onCompletion(() -> log.debug("Side story auto-run stream completed sideStory={}", id));

        return emitter;
    }
}
