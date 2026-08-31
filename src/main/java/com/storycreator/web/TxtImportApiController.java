package com.storycreator.web;

import com.storycreator.persistence.entity.TxtImportChapterEntity;
import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.txtimport.ChapterSplitConfigService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
                                                      @RequestParam(value = "genre", required = false) String genre) {
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

            TxtImportJobEntity job = importService.createJob(title, genre, content);
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
        }

        // Create project first
        Long projectId = importService.createProjectFromJob(jobId);

        // Start reverse engineering
        Flux<String> flux = reverseService.runReverseEngineering(jobId, projectId);
        bgService.startTask(jobId, flux);

        return ResponseEntity.ok(Map.of("status", "ok", "projectId", projectId));
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
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        GenerationTask task = bgService.getActiveTask(jobId);
        if (task == null) {
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data("没有活跃的逆向工程任务"));
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
                    emitter.send(SseEmitter.event().name("error").data(task.getErrorMessage()));
                    emitter.complete();
                    return;
                }

                subscription = task.getSink().asFlux()
                        .doOnNext(token -> {
                            try {
                                if (token.startsWith("[[RE_PHASE:")) {
                                    emitter.send(SseEmitter.event().name("phase").data(token));
                                } else if (token.equals("[[BG_STOPPED]]")) {
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
                                emitter.send(SseEmitter.event().name("error").data(
                                        SseErrorHelper.sanitize(error)));
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

        return emitter;
    }

    @PostMapping("/{jobId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long jobId) {
        bgService.stopTask(jobId);
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
