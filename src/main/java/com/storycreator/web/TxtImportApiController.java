package com.storycreator.web;

import com.storycreator.persistence.entity.TxtImportChapterEntity;
import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.txtimport.ChapterSplitConfigService;
import com.storycreator.txtimport.ReProtocol;
import com.storycreator.txtimport.TxtImportBackgroundService;
import com.storycreator.txtimport.TxtImportBackgroundService.GenerationTask;
import com.storycreator.txtimport.TxtImportService;
import com.storycreator.txtimport.TxtReverseEngineeringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/import/txt")
public class TxtImportApiController {

    private static final Logger log = LoggerFactory.getLogger(TxtImportApiController.class);

    private final TxtImportService importService;
    private final TxtReverseEngineeringService reverseService;
    private final TxtImportBackgroundService bgService;
    private final ChapterSplitConfigService configService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TxtImportApiController(TxtImportService importService,
                                  TxtReverseEngineeringService reverseService,
                                  TxtImportBackgroundService bgService,
                                  ChapterSplitConfigService configService) {
        this.importService = importService;
        this.reverseService = reverseService;
        this.bgService = bgService;
        this.configService = configService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "title", required = false) String title,
                                                      @RequestParam(value = "genre", required = false) String genre,
                                                      @RequestParam(value = "author", required = false) String author) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件内容为空"));
            }

            if (title == null || title.isBlank()) {
                String fileName = file.getOriginalFilename();
                if (fileName != null && fileName.contains(".")) {
                    title = fileName.substring(0, fileName.lastIndexOf('.'));
                } else {
                    title = fileName != null ? fileName : "未命名";
                }
            }

            TxtImportJobEntity job = importService.createJob(title, genre, author, content);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "jobId", job.getId(),
                    "title", job.getTitle(),
                    "wordCount", job.getTotalWordCount()
            ));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件读取失败: " + e.getMessage()));
        }
    }

    @PostMapping("/{jobId}/split")
    public ResponseEntity<Map<String, Object>> split(@PathVariable Long jobId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> configIdNums = body != null ? (List<Number>) body.get("configIds") : null;
        List<Long> configIds = configIdNums != null
                ? configIdNums.stream().map(Number::longValue).toList()
                : null;

        List<TxtImportChapterEntity> chapters = importService.splitJob(jobId, configIds);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "chapterCount", chapters.size(),
                "chapters", chapters.stream().map(this::chapterToMap).toList()
        ));
    }

    @PostMapping("/{jobId}/resplit")
    public ResponseEntity<Map<String, Object>> resplit(@PathVariable Long jobId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return split(jobId, body);
    }

    @PatchMapping("/{jobId}/chapter/{num}/title")
    public ResponseEntity<Map<String, Object>> updateTitle(@PathVariable Long jobId,
                                                           @PathVariable int num,
                                                           @RequestBody Map<String, String> body) {
        importService.updateChapterTitle(jobId, num, body.get("title"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/{jobId}/chapter/{num}/merge-next")
    public ResponseEntity<Map<String, Object>> mergeNext(@PathVariable Long jobId,
                                                          @PathVariable int num) {
        importService.mergeChapterWithNext(jobId, num);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/{jobId}/chapters")
    public ResponseEntity<Map<String, Object>> getChapters(@PathVariable Long jobId) {
        List<TxtImportChapterEntity> chapters = importService.getChapters(jobId);
        TxtImportJobEntity job = importService.getJob(jobId);
        return ResponseEntity.ok(Map.of(
                "chapters", chapters.stream().map(this::chapterToMap).toList(),
                "chapterCount", job.getChapterCount(),
                "totalWordCount", job.getTotalWordCount()
        ));
    }

    @PostMapping("/{jobId}/start-reverse")
    public ResponseEntity<Map<String, Object>> startReverse(@PathVariable Long jobId,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        if (bgService.isActive(jobId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "逆向工程任务已在运行中"));
        }

        TxtImportJobEntity job = importService.getJob(jobId);
        if (body != null) {
            if (body.containsKey("runWorldBuilding")) {
                job.setRunWorldBuilding(Boolean.TRUE.equals(body.get("runWorldBuilding")));
            }
            if (body.containsKey("runCharacters")) {
                job.setRunCharacters(Boolean.TRUE.equals(body.get("runCharacters")));
            }
            if (body.containsKey("runOutline")) {
                job.setRunOutline(Boolean.TRUE.equals(body.get("runOutline")));
            }
            if (body.containsKey("samplingStrategy")) {
                job.setSamplingStrategy((String) body.get("samplingStrategy"));
            }
            if (body.containsKey("samplingN")) {
                job.setSamplingN(((Number) body.get("samplingN")).intValue());
            }
            if (body.containsKey("modelConfigId") && body.get("modelConfigId") != null) {
                job.setModelConfigId(((Number) body.get("modelConfigId")).longValue());
            }
            if (body.containsKey("chaptersPerVolume") && body.get("chaptersPerVolume") != null) {
                int cpv = ((Number) body.get("chaptersPerVolume")).intValue();
                job.setChaptersPerVolume(cpv > 0 ? cpv : 30);
            }
        }

        importService.saveJob(job);

        // 首次创建项目；再次执行（断点续跑）复用同一项目
        Long projectId = importService.ensureProjectFromJob(jobId);
        job.setProjectId(projectId);
        importService.saveJob(job);

        // Start reverse engineering；取消判断在「每个单元」边界生效（阻塞式 LLM 调用无法中断）
        Flux<String> flux = reverseService.runReverseEngineering(jobId, projectId,
                () -> {
                    GenerationTask t = bgService.getActiveTask(jobId);
                    return t != null && t.isStopRequested();
                });
        bgService.startTask(jobId, flux);

        return ResponseEntity.ok(Map.of("status", "ok", "projectId", projectId));
    }

    /**
     * 扫描逆向工程完成度，返回各阶段计划（供 UI 展示「已完成 x/y」与「继续执行」）。
     * <p>若无活跃任务但 job 仍处于执行中状态，说明是进程重启/连接中断遗留，一律按可恢复处理。
     */
    @GetMapping("/{jobId}/reverse-plan")
    public ResponseEntity<Map<String, Object>> reversePlan(@PathVariable Long jobId) {
        TxtImportJobEntity job = importService.getJob(jobId);
        ReProtocol.RePlan plan = reverseService.plan(jobId);
        boolean active = bgService.isActive(jobId);
        boolean interrupted = !active
                && ("RUNNING".equals(plan.jobStatus()) || "INTERRUPTED".equals(plan.jobStatus()))
                && !plan.completed();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", interrupted ? "INTERRUPTED" : plan.jobStatus());
        body.put("active", active);
        body.put("interrupted", interrupted);
        body.put("projectId", job.getProjectId() != null ? job.getProjectId() : 0);
        body.put("chaptersPerVolume", plan.chaptersPerVolume());
        body.put("totalChapters", plan.totalChapters());
        body.put("totalVolumes", plan.totalVolumes());
        body.put("totalUnits", plan.totalUnits());
        body.put("completedUnits", plan.completedUnits());
        body.put("resumable", plan.resumable());
        body.put("completed", plan.completed());
        body.put("phases", plan.phases());
        return ResponseEntity.ok(body);
    }

    /** 清空逆向工程产出，回到「未开始」状态。 */
    @PostMapping("/{jobId}/reverse-reset")
    public ResponseEntity<Map<String, Object>> reverseReset(@PathVariable Long jobId) {
        if (bgService.isActive(jobId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "任务正在运行，请先停止"));
        }
        reverseService.resetProgress(jobId);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/{jobId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long jobId) {
        TxtImportJobEntity job = importService.getJob(jobId);
        boolean active = bgService.isActive(jobId);
        return ResponseEntity.ok(Map.of(
                "status", job.getStatus(),
                "active", active,
                "progressNote", job.getProgressNote() != null ? job.getProgressNote() : "",
                "errorMessage", job.getErrorMessage() != null ? job.getErrorMessage() : "",
                "projectId", job.getProjectId() != null ? job.getProjectId() : 0
        ));
    }

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long jobId) {
        // No aggressive timeout: reverse engineering legitimately takes minutes per LLM call
        // (and 5min was the root cause of the AsyncRequestTimeoutException crash). Heartbeats
        // below keep the async context / proxies alive, and onCompletion/onTimeout tidy up.
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + jobId);
            t.setDaemon(true);
            return t;
        });
        final Disposable[] subHolder = new Disposable[1];

        // Tear everything down once the stream ends (for any reason) so we never emit onto a
        // completed emitter, which is what produced the "ResponseBodyEmitter has already completed" spam.
        Runnable stopAll = () -> {
            if (subHolder[0] != null && !subHolder[0].isDisposed()) {
                subHolder[0].dispose();
            }
            heartbeat.shutdownNow();
        };

        emitter.onCompletion(stopAll);
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().comment("server-timeout"));
            } catch (Exception ignored) {
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            stopAll.run();
        });
        emitter.onError((Throwable t) -> {
            stopAll.run();
            try {
                emitter.completeWithError(t);
            } catch (Exception ignored) {
            }
        });

        // Comment-only heartbeat every 20s. EventSource ignores comment lines, but the byte
        // activity keeps the connection from idling out during long (3min+) LLM calls.
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                stopAll.run();
            }
        }, 20, 20, TimeUnit.SECONDS);

        GenerationTask task = bgService.getActiveTask(jobId);
        if (task == null) {
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data("没有活跃的逆向工程任务"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                } finally {
                    stopAll.run();
                }
            });
            return emitter;
        }

        executor.submit(() -> {
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
                    emitter.send(SseEmitter.event().name("error").data(task.getErrorMessage()));
                    emitter.complete();
                    return;
                }

                Disposable subscription = task.getSink().asFlux()
                        .doOnNext(token -> {
                            try {
                                dispatchSse(emitter, token);
                            } catch (Exception e) {
                                // IOException (client gone) or IllegalStateException (already completed) -> stop forwarding
                                stopAll.run();
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                if (task.isCompleted()) {
                                    emitter.send(SseEmitter.event().name("done").data("complete"));
                                }
                                emitter.complete();
                            } catch (Exception e) {
                                // ignore
                            } finally {
                                stopAll.run();
                            }
                        })
                        .doOnError(error -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data(
                                        SseErrorHelper.sanitize(error)));
                            } catch (Exception e) {
                                // ignore
                            }
                            try {
                                emitter.completeWithError(error);
                            } catch (Exception ignored) {
                            } finally {
                                stopAll.run();
                            }
                        })
                        .subscribe();
                subHolder[0] = subscription;

                while (!subscription.isDisposed()) {
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                if (subHolder[0] != null && !subHolder[0].isDisposed()) {
                    subHolder[0].dispose();
                }
                stopAll.run();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

    /**
     * 把逆向工程通道内的控制标记分流成不同的 SSE 事件。
     * <p>注意前缀匹配顺序：{@code [[RE_PHASE_DONE:} / {@code [[RE_PHASE_SKIP:} 必须以
     * {@code [[RE_PHASE:} 之前判断。
     */
    private void dispatchSse(SseEmitter emitter, String token) throws IOException {
        if (token.startsWith("[[RE_PHASE_DONE:")) {
            emitter.send(SseEmitter.event().name("phase-done")
                    .data(ReProtocol.unwrap(token, "[[RE_PHASE_DONE:")));
        } else if (token.startsWith("[[RE_PHASE_SKIP:")) {
            emitter.send(SseEmitter.event().name("phase-skip")
                    .data(ReProtocol.unwrap(token, "[[RE_PHASE_SKIP:")));
        } else if (token.startsWith("[[RE_PHASE:")) {
            emitter.send(SseEmitter.event().name("phase").data(ReProtocol.unwrap(token, "[[RE_PHASE:")));
        } else if (token.startsWith("[[RE_PLAN:")) {
            emitter.send(SseEmitter.event().name("plan").data(ReProtocol.unwrap(token, "[[RE_PLAN:")));
        } else if (token.startsWith("[[RE_ITEM:")) {
            emitter.send(SseEmitter.event().name("item").data(ReProtocol.unwrap(token, "[[RE_ITEM:")));
        } else if (token.startsWith("[[RE_NOTE:")) {
            emitter.send(SseEmitter.event().name("note").data(ReProtocol.unwrap(token, "[[RE_NOTE:")));
        } else if (token.startsWith("[[RE_PROGRESS:")) {
            emitter.send(SseEmitter.event().name("progress").data(ReProtocol.unwrap(token, "[[RE_PROGRESS:")));
        } else if (token.equals("[[BG_STOPPED]]")) {
            emitter.send(SseEmitter.event().name("stopped").data("stopped"));
        } else if (token.startsWith("[[BG_ERROR:")) {
            emitter.send(SseEmitter.event().name("error")
                    .data(token.substring(11, token.length() - 2)));
        } else {
            emitter.send(SseEmitter.event().name("token").data(token));
        }
    }

    @PostMapping("/{jobId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long jobId) {
        bgService.stopTask(jobId);
        reverseService.markStopped(jobId);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/test-pattern")
    public ResponseEntity<Map<String, Object>> testPattern(@RequestBody Map<String, String> body) {
        String pattern = body.get("pattern");
        String sampleText = body.get("sampleText");
        if (pattern == null || sampleText == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供正则表达式和示例文本"));
        }
        try {
            var result = configService.testPattern(pattern, sampleText);
            return ResponseEntity.ok(Map.of(
                    "matchCount", result.matchCount(),
                    "matches", result.matches()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> chapterToMap(TxtImportChapterEntity ch) {
        String preview = ch.getContent() != null && ch.getContent().length() > 150
                ? ch.getContent().substring(0, 150) + "..."
                : ch.getContent();
        return Map.of(
                "number", ch.getChapterNumber(),
                "title", ch.getTitle() != null ? ch.getTitle() : "",
                "wordCount", ch.getWordCount(),
                "preview", preview != null ? preview : ""
        );
    }
}
