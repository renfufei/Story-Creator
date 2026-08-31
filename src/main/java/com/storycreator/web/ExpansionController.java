package com.storycreator.web;

import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.workflow.background.ExpansionBackgroundService;
import com.storycreator.workflow.background.ExpansionBackgroundService.GenerationTask;
import com.storycreator.workflow.engine.ExpansionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.transaction.annotation.Transactional;

@Controller
@RequestMapping("/projects/{projectId}/expansion")
public class ExpansionController {

    private static final Logger log = LoggerFactory.getLogger(ExpansionController.class);

    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final ExpansionService expansionService;
    private final ExpansionBackgroundService bgService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ExpansionController(ProjectRepository projectRepository,
                               ChapterRepository chapterRepository,
                               ExpansionService expansionService,
                               ExpansionBackgroundService bgService) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.expansionService = expansionService;
        this.bgService = bgService;
    }

    @GetMapping
    public String page(@PathVariable Long projectId, Model model) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<Map<String, Object>> chaptersJs = chapters.stream().map(ch -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("chapterNumber", ch.getChapterNumber());
            m.put("title", ch.getTitle());
            m.put("wordCount", ch.getWordCount());
            m.put("expansionStatus", ch.getExpansionStatus());
            m.put("content", ch.getContent() != null && !ch.getContent().isEmpty() ? "Y" : null);
            return m;
        }).toList();
        model.addAttribute("project", project);
        model.addAttribute("chapters", chaptersJs);
        return "expansion";
    }

    @PostMapping("/mark")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> markChapters(@PathVariable Long projectId,
                                                             @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> chapterNumbers = (List<Number>) body.get("chapterNumbers");
        boolean marked = (Boolean) body.getOrDefault("marked", true);

        int count = 0;
        for (Number num : chapterNumbers) {
            chapterRepository.findByProjectIdAndChapterNumber(projectId, num.intValue()).ifPresent(ch -> {
                if (marked) {
                    if (ch.getExpansionStatus() == null) {
                        ch.setExpansionStatus("PENDING");
                        chapterRepository.save(ch);
                    }
                } else {
                    if ("PENDING".equals(ch.getExpansionStatus())) {
                        ch.setExpansionStatus(null);
                        chapterRepository.save(ch);
                    }
                }
            });
            count++;
        }
        return ResponseEntity.ok(Map.of("status", "ok", "count", count));
    }

    @PutMapping("/guidance")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveGuidance(@PathVariable Long projectId,
                                                             @RequestBody Map<String, String> body) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        project.setExpansionGuidance(body.get("guidance"));
        projectRepository.save(project);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long projectId) {
        if (bgService.isActive(projectId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "扩写任务已在运行中"));
        }

        Flux<String> flux = expansionService.runBatchExpansion(projectId);
        bgService.startExpansion(projectId, flux, null, null);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/stop")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long projectId) {
        bgService.stopExpansion(projectId);
        // dispose() 取消 Reactor 链不会触发 doOnError，显式回退当前章状态以免卡在 GENERATING
        int reset = chapterRepository.updateExpansionStatusByProjectAndStatus(projectId, "GENERATING", "PENDING");
        return ResponseEntity.ok(Map.of("status", "ok", "resetCount", reset));
    }

    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long projectId) {
        boolean active = bgService.isActive(projectId);
        List<ChapterEntity> pending = chapterRepository
                .findByProjectIdAndExpansionStatusOrderByChapterNumber(projectId, "PENDING");
        List<ChapterEntity> expanded = chapterRepository
                .findByProjectIdAndExpansionStatusOrderByChapterNumber(projectId, "EXPANDED");
        List<ChapterEntity> generating = chapterRepository
                .findByProjectIdAndExpansionStatusOrderByChapterNumber(projectId, "GENERATING");

        return ResponseEntity.ok(Map.of(
                "active", active,
                "pendingCount", pending.size(),
                "expandedCount", expanded.size(),
                "generatingCount", generating.size()
        ));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long projectId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        GenerationTask task = bgService.getActiveTask(projectId);
        if (task == null) {
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data("没有活跃的扩写任务"));
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
                                if (token.startsWith("[[EXPAND:")) {
                                    emitter.send(SseEmitter.event().name("expand-section").data(token));
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

    @PostMapping("/rollback/{chapterNumber}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable Long projectId,
                                                         @PathVariable int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));

        if (chapter.getContentBeforeExpansion() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有可回滚的原文备份"));
        }

        chapter.setContent(chapter.getContentBeforeExpansion());
        chapter.setWordCount(chapter.getContent().length());
        chapter.setExpansionStatus("PENDING");
        chapter.setContentBeforeExpansion(null);
        chapterRepository.save(chapter);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/confirm/{chapterNumber}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Long projectId,
                                                        @PathVariable int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));

        chapter.setExpansionStatus(null);
        chapter.setContentBeforeExpansion(null);
        chapterRepository.save(chapter);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/confirm-all")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> confirmAll(@PathVariable Long projectId) {
        List<ChapterEntity> expanded = chapterRepository
                .findByProjectIdAndExpansionStatusOrderByChapterNumber(projectId, "EXPANDED");
        for (ChapterEntity ch : expanded) {
            ch.setExpansionStatus(null);
            ch.setContentBeforeExpansion(null);
        }
        chapterRepository.saveAll(expanded);
        return ResponseEntity.ok(Map.of("status", "ok", "count", expanded.size()));
    }
}
