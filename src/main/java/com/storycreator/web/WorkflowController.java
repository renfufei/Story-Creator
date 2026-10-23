package com.storycreator.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;

import com.storycreator.core.domain.CharacterStateDimension;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.CharacterStateDimensionService;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.core.service.ProjectWriteGuard;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.volume.VolumeChapterGroup;
import com.storycreator.volume.VolumeService;
import com.storycreator.workflow.background.BackgroundGenerationService;
import com.storycreator.workflow.engine.WorldFacetElaborationService;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/projects/{projectId}")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);
    private WorkflowEngine workflowEngine;
    private ProjectRepository projectRepository;
    private WorkflowStateRepository workflowStateRepository;
    private ChapterRepository chapterRepository;
    private CharacterRepository characterRepository;
    private StoryOutlineRepository storyOutlineRepository;
    private ChapterOutlineRepository chapterOutlineRepository;
    private VolumeOutlineRepository volumeOutlineRepository;
    private AiModelConfigRepository modelConfigRepository;
    private StepGuidanceRepository stepGuidanceRepository;
    private StepModelConfigRepository stepModelConfigRepository;
    private ProofreadingReportRepository proofreadingReportRepository;
    private WorldSettingRepository worldSettingRepository;
    private GlobalSettingService globalSettingService;
    private BackgroundGenerationService backgroundGenerationService;
    private AutoRunStepConfigRepository autoRunStepConfigRepository;
    private CharacterStateDimensionService characterStateDimensionService;
    private WorldFacetElaborationService worldFacetElaborationService;
    private VolumeService volumeService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public void setVolumeService(VolumeService volumeService) {
        this.volumeService = volumeService;
    }

    /**
     * 路由段 -> 工作流步骤 的映射。拆分成独立接口后，前端按 /workflow/{step}/data 访问，
     * 不再通过 ?step= 参数区分（避免单一巨页 + 单一带参接口的复杂度）。
     */
    private static final Map<String, WorkflowStep> ROUTE_TO_STEP = Map.of(
            "world-building", WorkflowStep.WORLD_BUILDING,
            "characters", WorkflowStep.CHARACTER_DESIGN,
            "outline", WorkflowStep.OUTLINE_GENERATION,
            "chapters", WorkflowStep.CHAPTER_WRITING,
            "polishing", WorkflowStep.POLISHING,
            "proofreading", WorkflowStep.PROOFREADING
    );

    @Autowired
    public void setWorkflowEngine(WorkflowEngine workflowEngine) {
        this.workflowEngine = workflowEngine;
    }

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Autowired
    public void setWorkflowStateRepository(WorkflowStateRepository workflowStateRepository) {
        this.workflowStateRepository = workflowStateRepository;
    }

    @Autowired
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setCharacterRepository(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    @Autowired
    public void setStoryOutlineRepository(StoryOutlineRepository storyOutlineRepository) {
        this.storyOutlineRepository = storyOutlineRepository;
    }

    @Autowired
    public void setChapterOutlineRepository(ChapterOutlineRepository chapterOutlineRepository) {
        this.chapterOutlineRepository = chapterOutlineRepository;
    }

    @Autowired
    public void setVolumeOutlineRepository(VolumeOutlineRepository volumeOutlineRepository) {
        this.volumeOutlineRepository = volumeOutlineRepository;
    }

    @Autowired
    public void setModelConfigRepository(AiModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    @Autowired
    public void setStepGuidanceRepository(StepGuidanceRepository stepGuidanceRepository) {
        this.stepGuidanceRepository = stepGuidanceRepository;
    }

    @Autowired
    public void setStepModelConfigRepository(StepModelConfigRepository stepModelConfigRepository) {
        this.stepModelConfigRepository = stepModelConfigRepository;
    }

    @Autowired
    public void setProofreadingReportRepository(ProofreadingReportRepository proofreadingReportRepository) {
        this.proofreadingReportRepository = proofreadingReportRepository;
    }

    @Autowired
    public void setWorldSettingRepository(WorldSettingRepository worldSettingRepository) {
        this.worldSettingRepository = worldSettingRepository;
    }

    @Autowired
    public void setGlobalSettingService(GlobalSettingService globalSettingService) {
        this.globalSettingService = globalSettingService;
    }

    @Autowired
    public void setBackgroundGenerationService(BackgroundGenerationService backgroundGenerationService) {
        this.backgroundGenerationService = backgroundGenerationService;
    }

    @Autowired
    public void setAutoRunStepConfigRepository(AutoRunStepConfigRepository autoRunStepConfigRepository) {
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
    }

    @Autowired
    public void setCharacterStateDimensionService(CharacterStateDimensionService characterStateDimensionService) {
        this.characterStateDimensionService = characterStateDimensionService;
    }

    @Autowired
    public void setWorldFacetElaborationService(WorldFacetElaborationService worldFacetElaborationService) {
        this.worldFacetElaborationService = worldFacetElaborationService;
    }

    // ==================== 「已完本 / 已废弃」写入闸门 ====================

    /**
     * 启动期拦截：已完本 / 已废弃的项目不允许再启动「会改动正文」的流程
     * （AI 生成、校对修复、角色精炼、大纲重生成、全局替换、分面重展开）。
     *
     * <p>★ 只在「新任务开始」时调用一次：不放进 Flux 的 doOnNext、不放进循环。
     * 否则跑到一半把项目改成已完本，正在写的章节会突然失败、状态机卡在 GENERATING。
     *
     * @return 非 {@code null} 表示已拒绝，直接 return 给前端（前端照旧收 SSE error 事件）
     */
    private SseEmitter rejectIfSealed(Long projectId, String action) {
        ProjectEntity project = projectRepository.findById(projectId).orElse(null);
        String deny = project == null ? null : ProjectWriteGuard.denyMessage(project.getStatus(), action);
        return deny == null ? null : SseErrorHelper.rejected(deny);
    }

    /** JSON 端点版：返回拒绝文案，{@code null} 表示放行 */
    private String sealedReason(Long projectId, String action) {
        ProjectEntity project = projectRepository.findById(projectId).orElse(null);
        return project == null ? null : ProjectWriteGuard.denyMessage(project.getStatus(), action);
    }


    /**
     * 静态化后，工作流页面由 /pages/workflow.html 提供，本端点以 JSON 返回原 Thymeleaf 注入的
     * __WORKFLOW_DATA__ 全部字段（外加静态页所需的新字段）。前端通过同步 XHR 拉取后驱动 Alpine。
     */
    @GetMapping(value = "/workflow/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> workflowData(@PathVariable Long projectId,
                                                          @RequestParam(required = false) WorkflowStep step) {
        return ResponseEntity.ok(buildWorkflowBootstrap(projectId, step));
    }

    private Map<String, Object> buildWorkflowBootstrap(Long projectId, WorkflowStep step) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        // Allow viewing any step, default to project's current step
        WorkflowStep viewStep = (step != null) ? step : project.getCurrentStep();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("projectTitle", project.getTitle());
        data.put("genreDisplayName", project.getGenre() != null ? project.getGenre().getDisplayName() : "");
        data.put("viewStepDisplayName", viewStep.getDisplayName());
        data.put("currentStep", viewStep.name());
        data.put("currentStepOrder", viewStep.getOrder());
        data.put("projectCurrentStep", project.getCurrentStep().name());
        data.put("projectCurrentStepOrder", project.getCurrentStep().getOrder());
        data.put("isCurrentStep", viewStep == project.getCurrentStep());
        data.put("isLastStep", viewStep.next() == null);
        data.put("hasNextStep", viewStep.next() != null);
        data.put("totalChapters", project.getTotalChapters());
        data.put("chapterWordCount", project.getChapterWordCount());
        data.put("chapterWordCountMin", project.getChapterWordCountMin());
        data.put("chapterWordCountMax", project.getChapterWordCountMax());
        data.put("autoMode", project.isAutoMode());

        // Get content for viewed step (skip for chapter-level steps, content loaded via AJAX)
        if (viewStep != WorkflowStep.CHAPTER_WRITING && viewStep != WorkflowStep.POLISHING && viewStep != WorkflowStep.PROOFREADING) {
            workflowStateRepository.findByProjectIdAndStep(projectId, viewStep)
                    .ifPresent(state -> data.put("currentContent", state.getEffectiveContent()));
        } else {
            data.put("currentContent", "");
        }

        // Pass step confirmed status
        boolean stepConfirmed = workflowStateRepository.findByProjectIdAndStep(projectId, viewStep)
                .map(s -> s.getStatus() == StepStatus.CONFIRMED)
                .orElse(false);
        data.put("stepConfirmed", stepConfirmed);

        // Model configs for step-level selection (text models only) — 仅暴露 id + displayName
        List<Map<String, Object>> modelConfigs = modelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)
                .stream().map(mc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", mc.getId());
                    m.put("displayName", mc.getDisplayName());
                    return m;
                }).toList();
        data.put("modelConfigs", modelConfigs);

        // Viewed step's model config id (from step_model_configs table)
        stepModelConfigRepository.findByProjectIdAndStep(projectId, viewStep)
                .ifPresent(smc -> data.put("stepModelConfigId", smc.getModelConfigId()));

        // Load step guidance
        String stepGuidance = stepGuidanceRepository.findByProjectIdAndStep(projectId, viewStep)
                .map(StepGuidanceEntity::getGuidance)
                .orElse("");
        data.put("stepGuidance", stepGuidance);

        // Auto-run step configs: ensure rows exist for main steps + sub-steps, pass to model
        if (project.isAutoMode()) {
            List<AutoRunStepConfigEntity> configs = autoRunStepConfigRepository.findByProjectId(projectId);
            Set<String> existingKeys = new HashSet<>();
            for (AutoRunStepConfigEntity c : configs) {
                existingKeys.add(c.getStep());
            }
            // Ensure main steps
            for (WorkflowStep ws : WorkflowStep.values()) {
                if (!existingKeys.contains(ws.name())) {
                    boolean defaultEnabled = (ws != WorkflowStep.PROOFREADING);
                    AutoRunStepConfigEntity newConfig = new AutoRunStepConfigEntity(projectId, ws.name(), defaultEnabled);
                    autoRunStepConfigRepository.save(newConfig);
                    configs.add(newConfig);
                    existingKeys.add(ws.name());
                }
            }
            // Ensure sub-steps
            String strategy = "DEFAULT";
            List<String> subSteps = getSubStepsForStrategy(strategy);
            for (String subStep : subSteps) {
                if (!existingKeys.contains(subStep)) {
                    AutoRunStepConfigEntity newConfig = new AutoRunStepConfigEntity(projectId, subStep, true);
                    autoRunStepConfigRepository.save(newConfig);
                    configs.add(newConfig);
                    existingKeys.add(subStep);
                }
            }
            Map<String, Boolean> stepConfigMap = new LinkedHashMap<>();
            for (WorkflowStep ws : WorkflowStep.values()) {
                for (AutoRunStepConfigEntity c : configs) {
                    if (ws.name().equals(c.getStep())) {
                        stepConfigMap.put(ws.name(), c.isEnabled());
                        break;
                    }
                }
            }
            for (String subStep : subSteps) {
                for (AutoRunStepConfigEntity c : configs) {
                    if (subStep.equals(c.getStep())) {
                        stepConfigMap.put(subStep, c.isEnabled());
                        break;
                    }
                }
            }
            data.put("autoRunStepConfigs", stepConfigMap);
            data.put("autoRunStrategy", strategy);
        } else {
            data.put("autoRunStepConfigs", new LinkedHashMap<>());
            data.put("autoRunStrategy", "DEFAULT");
        }

        // Character state dimension configs
        var dimEntities = characterStateDimensionService.ensureAndGet(projectId);
        List<Map<String, Object>> dimConfigs = new ArrayList<>();
        for (var dimEntity : dimEntities) {
            Map<String, Object> dimMap = new LinkedHashMap<>();
            dimMap.put("key", dimEntity.getDimKey().name());
            dimMap.put("displayName", dimEntity.getDimKey().getDisplayName());
            dimMap.put("enabled", dimEntity.isEnabled());
            dimMap.put("defaultEnabled", dimEntity.getDimKey().isDefaultEnabled());
            dimConfigs.add(dimMap);
        }
        data.put("characterStateDims", dimConfigs);

        return data;
    }

    /**
     * 按步骤拆分的数据接口（替代 /workflow/data?step=X 的参数区分方式）。
     * 路径：/projects/{projectId}/workflow/{step}/data
     * step ∈ {world-building, characters, outline, chapters, polishing, proofreading}
     */
    @GetMapping(value = "/workflow/{step}/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> workflowStepData(@PathVariable Long projectId,
                                                              @PathVariable String step) {
        WorkflowStep ws = ROUTE_TO_STEP.get(step);
        if (ws == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知的工作流步骤: " + step));
        }
        return ResponseEntity.ok(buildWorkflowBootstrap(projectId, ws));
    }

    @PostMapping("/step-model")
    public String setStepModel(@PathVariable Long projectId,
                              @RequestParam WorkflowStep step,
                              @RequestParam(required = false) Long modelConfigId) {
        if (modelConfigId == null || modelConfigId == 0) {
            // Remove step model config if clearing
            stepModelConfigRepository.findByProjectIdAndStep(projectId, step)
                    .ifPresent(entity -> stepModelConfigRepository.delete(entity));
        } else {
            StepModelConfigEntity entity = stepModelConfigRepository
                    .findByProjectIdAndStep(projectId, step)
                    .orElseGet(() -> {
                        StepModelConfigEntity e = new StepModelConfigEntity();
                        e.setProjectId(projectId);
                        e.setStep(step);
                        return e;
                    });
            entity.setModelConfigId(modelConfigId);
            stepModelConfigRepository.save(entity);
        }
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/step-guidance")
    @ResponseBody
    public ResponseEntity<Map<String, String>> saveStepGuidance(@PathVariable Long projectId,
                                                                 @RequestParam WorkflowStep step,
                                                                 @RequestParam String guidance) {
        StepGuidanceEntity entity = stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    StepGuidanceEntity sg = new StepGuidanceEntity();
                    sg.setProjectId(projectId);
                    sg.setStep(step);
                    return sg;
                });
        entity.setGuidance(guidance);
        stepGuidanceRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter generate(@PathVariable Long projectId,
                               @RequestParam WorkflowStep step,
                               @RequestParam(defaultValue = "0") int chapter,
                               @RequestParam(required = false) List<Long> materialIds) {
        /* 已完本 / 已废弃：立刻拒绝，不创建 emitter、不标 GENERATING */
        SseEmitter sealed = rejectIfSealed(projectId, "进行AI生成");
        if (sealed != null) return sealed;

        // Step order validation: reject requests for steps too far ahead of current progress
        // Allow CHAPTER_WRITING/POLISHING/PROOFREADING freely (they operate on individual chapters)
        if (step.getOrder() <= WorkflowStep.OUTLINE_GENERATION.getOrder()) {
            List<WorkflowStateEntity> states = workflowStateRepository.findByProjectId(projectId);
            int maxConfirmedOrder = 0;
            for (WorkflowStateEntity ws : states) {
                if (ws.getStatus() == StepStatus.CONFIRMED || ws.getStatus() == StepStatus.PARTIALLY_DONE) {
                    maxConfirmedOrder = Math.max(maxConfirmedOrder, ws.getStep().getOrder());
                }
            }
            // Allow generating current step (maxConfirmedOrder + 1) and any previous steps
            if (step.getOrder() > maxConfirmedOrder + 1) {
                throw new IllegalStateException("请先完成前置步骤再生成此步骤");
            }
        }

        // Multi-substep flows need timeout proportional to number of AI calls
        long timeoutMs;
        if (step == WorkflowStep.OUTLINE_GENERATION) {
            // volumes + chapters + summary: each call ~aiTimeout seconds
            int totalCh = projectRepository.findById(projectId).map(ProjectEntity::getTotalChapters).orElse(30);
            int volumeCount = (totalCh + 9) / 10;
            int totalCalls = volumeCount + totalCh + totalCh + 1; // volumes + chapters + refine + summary
            timeoutMs = (long) totalCalls * globalSettingService.getAiTimeoutSeconds() * 1000L;
        } else if (step == WorkflowStep.CHARACTER_DESIGN || step == WorkflowStep.PROOFREADING) {
            timeoutMs = 120 * 60 * 1000L; // 2 hours
        } else {
            timeoutMs = globalSettingService.getAiTimeoutSeconds() * 1000L;
        }
        log.info("[P{}] SSE generate request step={} chapter={} timeout={}s", projectId, step, chapter, timeoutMs / 1000);
        SseEmitter emitter = new SseEmitter(timeoutMs);

        // Track whether generation completed successfully
        final boolean[] completed = {false};

        // On timeout or client disconnect, reset stuck GENERATING status (unless bg task is active)
        Runnable resetStatus = () -> {
            if (!completed[0] && !backgroundGenerationService.isActive(projectId, step, chapter)) {
                log.warn("[P{}] SSE disconnected/timeout step={} chapter={} (generation incomplete)", projectId, step, chapter);
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            }
        };
        emitter.onTimeout(resetStatus);
        emitter.onCompletion(resetStatus);

        executor.submit(() -> {
            StringBuilder fullContent = new StringBuilder();
            try {
                workflowEngine.generate(projectId, step, chapter,
                                materialIds != null ? materialIds : Collections.emptyList())
                        .doOnNext(token -> {
                            try {
                                if (step == WorkflowStep.CHARACTER_DESIGN && token.startsWith("[[CHAR:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("char-section")
                                            .data(token));
                                } else if (step == WorkflowStep.OUTLINE_GENERATION && token.startsWith("[[SECTION:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("outline-section")
                                            .data(token));
                                } else if (step == WorkflowStep.PROOFREADING && token.startsWith("[[PROOFREAD:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("proofread-section")
                                            .data(token));
                                } else if (step == WorkflowStep.PROOFREADING && token.startsWith("[[PROOFREAD_FIX:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("proofread-fix-section")
                                            .data(token));
                                } else {
                                    fullContent.append(token);
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(token));
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                completed[0] = true;
                                workflowEngine.saveGeneratedContent(projectId, step, fullContent.toString(), chapter);
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                                // After SSE is closed, async generate character states (no concurrency issue in manual mode)
                                if (step == WorkflowStep.CHAPTER_WRITING && chapter > 0) {
                                    final int chNum = chapter;
                                    Thread.startVirtualThread(() -> {
                                        try {
                                            workflowEngine.generateCharacterStates(projectId, chNum);
                                        } catch (Exception e) {
                                            log.warn("[P{}] Failed to generate character states for ch{}: {}", projectId, chNum, e.getMessage());
                                        }
                                    });
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("Generation error", error);
                            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(SseErrorHelper.sanitize(error)));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Stream execution error", e);
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                try {
                    emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(e)));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping("/confirm")
    public String confirmStep(@PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.confirmStep(projectId, step);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/confirm-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, String>> confirmStepAjax(
            @PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.confirmStep(projectId, step);
        WorkflowStep nextStep = step.next();
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("nextStep", nextStep != null ? nextStep.name() : "COMPLETED");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/confirm-only-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, String>> confirmStepOnlyAjax(
            @PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.confirmStepOnly(projectId, step);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/advance-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, String>> advanceStepAjax(
            @PathVariable Long projectId, @RequestParam WorkflowStep step) {
        workflowEngine.advanceStep(projectId, step);
        WorkflowStep nextStep = step.next();
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("nextStep", nextStep != null ? nextStep.name() : "COMPLETED");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/save-edit")
    public String saveEdit(@PathVariable Long projectId,
                          @RequestParam WorkflowStep step,
                          @RequestParam String content) {
        workflowEngine.saveUserEdit(projectId, step, content);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/chapters/{chapterNumber}/save")
    public String saveChapter(@PathVariable Long projectId,
                             @PathVariable int chapterNumber,
                             @RequestParam String content) {
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return c;
                });
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setStatus(StepStatus.CONFIRMED);
        chapterRepository.save(chapter);
        // 接受/保存正文时把卷归属落下来（与生成提示词同一个解析口径）
        volumeService.stampChapterVolume(projectId, chapterNumber);
        return "redirect:/projects/" + projectId + "/workflow";
    }

    @PostMapping("/chapters/{chapterNumber}/save-ajax")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveChapterAjax(@PathVariable Long projectId,
                                                                @PathVariable int chapterNumber,
                                                                @RequestParam String content) {
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return c;
                });
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setStatus(StepStatus.CONFIRMED);
        chapterRepository.save(chapter);
        // 接受/保存正文时把卷归属落下来（与生成提示词同一个解析口径）
        var chapterVolume = volumeService.stampChapterVolume(projectId, chapterNumber).orElse(null);
        return ResponseEntity.ok(Map.of("status", "ok", "wordCount", content.length(),
                "volumeNumber", chapterVolume != null ? chapterVolume.volumeNumber() : 0));
    }

    /**
     * JSON endpoint to get chapter list (for AJAX refresh)
     */
    @GetMapping("/chapters/list")
    @ResponseBody
    public List<Map<String, Object>> getChapterList(@PathVariable Long projectId) {
        // 章节大纲存于 chapter_outlines 表（正常工作流与 TXT 逆向工程共用），按章号取出供前端展示
        Map<Integer, String> outlineSummaries = new HashMap<>();
        for (ChapterOutlineEntity co : chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId)) {
            outlineSummaries.put(co.getChapterNumber(), co.getSummary());
        }
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        return chapters.stream().map(ch -> {
            Map<String, Object> map = new HashMap<>();
            map.put("chapterNumber", ch.getChapterNumber());
            map.put("title", ch.getTitle());
            map.put("summary", outlineSummaries.get(ch.getChapterNumber()));
            map.put("wordCount", ch.getWordCount());
            map.put("status", ch.getStatus().name());
            map.put("polishStatus", ch.getPolishStatus() != null ? ch.getPolishStatus().name() : "NOT_STARTED");
            map.put("polishNote", ch.getPolishNote());
            map.put("proofreadStatus", ch.getProofreadStatus() != null ? ch.getProofreadStatus().name() : "NOT_STARTED");
            map.put("proofreadFixStatus", ch.getProofreadFixStatus() != null ? ch.getProofreadFixStatus().name() : "NOT_STARTED");
            map.put("hasDraft", ch.getContentDraft() != null && !ch.getContentDraft().isBlank());
            return map;
        }).toList();
    }

    /**
     * JSON endpoint to get single chapter content (for preview)
     */
    @GetMapping("/chapters/{chapterNumber}/content")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getChapterContent(@PathVariable Long projectId,
                                                                  @PathVariable int chapterNumber) {
        return chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(ch -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("chapterNumber", ch.getChapterNumber());
                    map.put("title", ch.getTitle());
                    map.put("content", ch.getContent());
                    map.put("contentDraft", ch.getContentDraft());
                    map.put("wordCount", ch.getWordCount());
                    map.put("polishNote", ch.getPolishNote());
                    map.put("polishStatus", ch.getPolishStatus() != null ? ch.getPolishStatus().name() : "NOT_STARTED");
                    map.put("contentBeforeFix", ch.getContentBeforeFix());
                    map.put("proofreadFixStatus", ch.getProofreadFixStatus() != null ? ch.getProofreadFixStatus().name() : "NOT_STARTED");
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Save polish note for a chapter
     */
    @PostMapping("/chapters/{chapterNumber}/polish-note")
    @ResponseBody
    public ResponseEntity<Map<String, String>> savePolishNote(@PathVariable Long projectId,
                                                              @PathVariable int chapterNumber,
                                                              @RequestParam String note) {
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        chapter.setPolishNote(note);
        // Mark as needing re-polish if note is non-empty
        if (note != null && !note.isBlank()) {
            chapter.setPolishStatus(StepStatus.NOT_STARTED);
        }
        chapterRepository.save(chapter);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * SSE endpoint to run proofread fix on a single chapter
     */
    @GetMapping(value = "/proofread-fix/{chapterNumber}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter proofreadFix(@PathVariable Long projectId, @PathVariable int chapterNumber) {
        /* 校对修复会把正文改写后落库 ⇒ 已完本 / 已废弃的不许启动 */
        SseEmitter sealed = rejectIfSealed(projectId, "进行AI校对修复");
        if (sealed != null) return sealed;

        long timeoutMs = globalSettingService.getAiTimeoutSeconds() * 10 * 1000L;
        log.info("[P{}] Proofread fix request chapter={} timeout={}s", projectId, chapterNumber, timeoutMs / 1000);
        SseEmitter emitter = new SseEmitter(timeoutMs);

        executor.submit(() -> {
            try {
                workflowEngine.proofreadFixSingleChapter(projectId, chapterNumber)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
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
                            log.error("Proofread fix error", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(error)));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Proofread fix execution error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(e)));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Get all characters for a project (sort_order > 0, i.e. individual cards)
     */
    @GetMapping("/characters")
    @ResponseBody
    public List<Map<String, Object>> getCharacters(@PathVariable Long projectId) {
        List<CharacterEntity> chars = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        return chars.stream().map(ch -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", ch.getId());
            map.put("name", ch.getName());
            map.put("gender", ch.getGender());
            map.put("age", ch.getAge());
            map.put("role", ch.getRole());
            map.put("personality", ch.getPersonality());
            map.put("appearance", ch.getAppearance());
            map.put("background", ch.getBackground());
            map.put("motivation", ch.getMotivation());
            map.put("abilities", ch.getAbilities());
            map.put("relationships", ch.getRelationships());
            map.put("description", ch.getDescription());
            map.put("sortOrder", ch.getSortOrder());
            map.put("status", ch.getStatus() != null ? ch.getStatus() : "GENERATED");
            map.put("characterType", ch.getCharacterType() != null ? ch.getCharacterType() : "INITIAL");
            map.put("startVolume", ch.getStartVolume());
            map.put("endVolume", ch.getEndVolume());
            return map;
        }).toList();
    }

    /**
     * Get character overview (sort_order=0) for a project
     */
    @GetMapping("/characters/overview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCharacterOverview(@PathVariable Long projectId) {
        return characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .map(overview -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("content", overview.getContent());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.ok(Map.of("content", "")));
    }

    @PostMapping("/characters/overview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveCharacterOverview(@PathVariable Long projectId,
                                                                      @RequestParam String content) {
        characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .ifPresent(overview -> {
                    overview.setContent(content);
                    characterRepository.save(overview);
                });
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * SSE endpoint to refine all character cards via AI
     */
    @GetMapping(value = "/characters/refine-all", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter refineAllCharacters(@PathVariable Long projectId) {
        SseEmitter sealed = rejectIfSealed(projectId, "精炼角色卡");
        if (sealed != null) return sealed;

        long timeoutMs = 120 * 60 * 1000L; // 2 hours
        SseEmitter emitter = new SseEmitter(timeoutMs);

        executor.submit(() -> {
            try {
                workflowEngine.refineAllCharacters(projectId)
                        .doOnNext(token -> {
                            try {
                                if (token.startsWith("[[CHAR:REFINE:")) {
                                    emitter.send(SseEmitter.event()
                                            .name("char-refine")
                                            .data(token));
                                } else {
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(token));
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
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
                            log.error("Character refine error", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(error)));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Character refine execution error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(e)));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Update a single character's structured fields
     */
    @PostMapping("/characters/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, String>> updateCharacter(@PathVariable Long projectId,
                                                               @PathVariable Long id,
                                                               @RequestParam(required = false) String name,
                                                               @RequestParam(required = false) String gender,
                                                               @RequestParam(required = false) String age,
                                                               @RequestParam(required = false) String role,
                                                               @RequestParam(required = false) String personality,
                                                               @RequestParam(required = false) String appearance,
                                                               @RequestParam(required = false) String background,
                                                               @RequestParam(required = false) String motivation,
                                                               @RequestParam(required = false) String abilities,
                                                               @RequestParam(required = false) String relationships,
                                                               @RequestParam(required = false) String description) {
        CharacterEntity ch = characterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        if (name != null) ch.setName(name);
        if (gender != null) ch.setGender(gender);
        if (age != null) ch.setAge(age);
        if (role != null) ch.setRole(role);
        if (personality != null) ch.setPersonality(personality);
        if (appearance != null) ch.setAppearance(appearance);
        if (background != null) ch.setBackground(background);
        if (motivation != null) ch.setMotivation(motivation);
        if (abilities != null) ch.setAbilities(abilities);
        if (relationships != null) ch.setRelationships(relationships);
        if (description != null) ch.setDescription(description);
        // 手工保存后，若角色仍处于「未生成」状态（PENDING 或空），升级为「已生成」，
        // 使其与 AI 生成的角色一致，可参与后续精修。
        if (ch.getStatus() == null || ch.getStatus().isBlank() || "PENDING".equals(ch.getStatus())) {
            ch.setStatus("GENERATED");
        }
        characterRepository.save(ch);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Add a new character manually
     */
    @PostMapping("/characters/add")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addCharacter(@PathVariable Long projectId,
                                                             @RequestParam String name) {
        // Get max sort_order for this project
        List<CharacterEntity> existing = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        int maxOrder = existing.stream().mapToInt(CharacterEntity::getSortOrder).max().orElse(0);

        CharacterEntity ch = new CharacterEntity();
        ch.setProjectId(projectId);
        ch.setName(name);
        // 手工创建的角色即视为「已生成」（由用户人工撰写），可直接参与后续 AI 精修；
        // 不再标记为 PENDING，避免界面显示成「未生成」而误导为待 AI 生成。
        ch.setStatus("GENERATED");
        ch.setSortOrder(maxOrder + 1);
        characterRepository.save(ch);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("id", ch.getId());
        return ResponseEntity.ok(result);
    }

    /**
     * Delete a character
     */
    @PostMapping("/characters/{id}/delete")
    @ResponseBody
    public ResponseEntity<Map<String, String>> deleteCharacter(@PathVariable Long projectId,
                                                               @PathVariable Long id) {
        CharacterEntity character = characterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + id));
        if (!character.getProjectId().equals(projectId)) {
            return ResponseEntity.status(403).body(Map.of("status", "error", "message", "角色不属于该项目"));
        }
        characterRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Get workflow state status for a step (used for polling from other tabs)
     */
    @GetMapping("/workflow-state-status")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getWorkflowStateStatus(@PathVariable Long projectId,
                                                                        @RequestParam WorkflowStep step) {
        String status = workflowStateRepository.findByProjectIdAndStep(projectId, step)
                .map(s -> s.getStatus().name())
                .orElse("NOT_STARTED");
        return ResponseEntity.ok(Map.of("status", status));
    }

    /**
     * Get all workflow states for the workflow state setting modal
     */
    @GetMapping("/workflow-states")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getWorkflowStates(@PathVariable Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        List<WorkflowStateEntity> states = workflowStateRepository.findByProjectId(projectId);

        Map<String, String> stepStatuses = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> allowedStatuses = new LinkedHashMap<>();
        for (WorkflowStep ws : WorkflowStep.values()) {
            String status = states.stream()
                    .filter(s -> s.getStep() == ws)
                    .map(s -> s.getStatus().name())
                    .findFirst()
                    .orElse("NOT_STARTED");
            stepStatuses.put(ws.name(), status);
            allowedStatuses.put(ws.name(), ws.getAllowedManualStatuses().stream()
                    .map(s -> Map.of("name", s.name(), "label", s.getDisplayName()))
                    .toList());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("currentStep", project.getCurrentStep().name());
        result.put("stepStatuses", stepStatuses);
        result.put("allowedStatuses", allowedStatuses);
        return ResponseEntity.ok(result);
    }

    /**
     * Save workflow states (set current step and per-step statuses)
     */
    @PostMapping("/workflow-states")
    @ResponseBody
    public ResponseEntity<Map<String, String>> saveWorkflowStates(@PathVariable Long projectId,
                                                                    @RequestBody Map<String, Object> payload) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        // Block changes while auto-run is active
        if (project.getAutoRunStatus() != null
                && (project.getAutoRunStatus().name().equals("RUNNING")
                    || project.getAutoRunStatus().name().equals("STOPPING"))) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "自动创作运行中，无法修改工作流状态"));
        }

        // Update current step
        String currentStepStr = (String) payload.get("currentStep");
        if (currentStepStr != null) {
            project.setCurrentStep(WorkflowStep.valueOf(currentStepStr));
            projectRepository.save(project);
        }

        // Update per-step statuses
        @SuppressWarnings("unchecked")
        Map<String, String> stepStatuses = (Map<String, String>) payload.get("stepStatuses");
        if (stepStatuses != null) {
            for (Map.Entry<String, String> entry : stepStatuses.entrySet()) {
                WorkflowStep ws = WorkflowStep.valueOf(entry.getKey());
                StepStatus status = StepStatus.valueOf(entry.getValue());

                // Validate status is allowed for this step
                if (!ws.getAllowedManualStatuses().contains(status)) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error",
                            "message", ws.getDisplayName() + " 不允许设置为 " + status.getDisplayName()));
                }

                WorkflowStateEntity state = workflowStateRepository
                        .findByProjectIdAndStep(projectId, ws)
                        .orElse(null);

                if (status == StepStatus.NOT_STARTED) {
                    // If set to NOT_STARTED, delete the state row if exists
                    if (state != null) {
                        workflowStateRepository.delete(state);
                    }
                } else {
                    // Create or update
                    if (state == null) {
                        state = new WorkflowStateEntity();
                        state.setProjectId(projectId);
                        state.setStep(ws);
                        state.setGeneratedContent("[manually set]");
                    }
                    state.setStatus(status);
                    workflowStateRepository.save(state);
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Get volume metadata (lightweight, for chapter grouping in writing/polishing/proofreading)
     *
     * <p>额外下发 {@code chapterNumbers}（该卷实际包含的章节号，由 {@link VolumeService} 统一解析）：
     * 用户在「分卷管理」调整过归属后，各卷的章节可能不再是连续区间，前端应优先按该列表分组，
     * 只有在它缺失（老缓存等场景）时才退回 {@code chapterStart}~{@code chapterEnd} 范围过滤。
     */
    @GetMapping("/volumes")
    @ResponseBody
    public List<Map<String, Object>> getVolumes(@PathVariable Long projectId) {
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        Map<Integer, List<Integer>> numbersByVolumeNumber = new HashMap<>();
        for (VolumeChapterGroup g : volumeService.resolveGroups(projectId)) {
            numbersByVolumeNumber.put(g.volumeNumber(), g.chapterNumbers());
        }
        return volumes.stream().map(vol -> {
            Map<String, Object> map = new LinkedHashMap<>();
            List<Integer> numbers = numbersByVolumeNumber.get(vol.getVolumeNumber());
            if (numbers == null) {
                numbers = List.of();
            }
            map.put("volumeNumber", vol.getVolumeNumber());
            map.put("title", vol.getTitle());
            map.put("arcName", vol.getArcName());
            map.put("chapterStart", numbers.isEmpty() ? vol.getChapterStart() : numbers.get(0));
            map.put("chapterEnd", numbers.isEmpty() ? vol.getChapterEnd() : numbers.get(numbers.size() - 1));
            map.put("chapterNumbers", numbers);
            return map;
        }).toList();
    }

    // --- Outline structured data endpoints ---

    /**
     * Get structured outline data (volumes + chapters + summary)
     */
    @GetMapping("/outline/data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getOutlineData(@PathVariable Long projectId) {
        Map<String, Object> result = new HashMap<>();

        // Story summary
        String storySummary = storyOutlineRepository.findByProjectId(projectId)
                .map(StoryOutlineEntity::getContent)
                .orElse("");
        result.put("storySummary", storySummary);

        // Volumes with their chapters
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        List<ChapterOutlineEntity> allChapters = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);

        Map<Integer, List<ChapterOutlineEntity>> chaptersByVolume = groupChaptersByVolume(volumes, allChapters);

        List<Map<String, Object>> volumeList = new ArrayList<>();
        for (VolumeOutlineEntity vol : volumes) {
            Map<String, Object> volMap = new HashMap<>();
            volMap.put("volumeNumber", vol.getVolumeNumber());
            volMap.put("title", vol.getTitle());
            volMap.put("arcName", vol.getArcName());
            volMap.put("arcSummary", vol.getArcSummary());

            List<ChapterOutlineEntity> mine = chaptersByVolume.get(vol.getVolumeNumber());
            if (mine == null) {
                mine = List.of();
            }
            // 卷的章范围用「实际归入本卷的章节」现算，而不是 volume_outlines 里那个可能过期的区间
            volMap.put("chapterStart", mine.isEmpty() ? vol.getChapterStart() : mine.get(0).getChapterNumber());
            volMap.put("chapterEnd", mine.isEmpty() ? vol.getChapterEnd() : mine.get(mine.size() - 1).getChapterNumber());

            List<Map<String, Object>> chapterList = mine.stream()
                    .map(ch -> {
                        Map<String, Object> chMap = new HashMap<>();
                        chMap.put("chapterNumber", ch.getChapterNumber());
                        chMap.put("title", ch.getTitle());
                        chMap.put("summary", ch.getSummary());
                        chMap.put("characterNames", ch.getCharacterNames());
                        chMap.put("status", ch.getStatus() != null ? ch.getStatus() : "COMPLETED");
                        chMap.put("originalSummary", ch.getOriginalSummary());
                        return chMap;
                    })
                    .toList();
            volMap.put("chapters", chapterList);
            volumeList.add(volMap);
        }
        result.put("volumes", volumeList);

        // If no volumes but chapters exist (legacy data), return flat chapter list
        if (volumes.isEmpty() && !allChapters.isEmpty()) {
            List<Map<String, Object>> chapterList = allChapters.stream()
                    .map(ch -> {
                        Map<String, Object> chMap = new HashMap<>();
                        chMap.put("chapterNumber", ch.getChapterNumber());
                        chMap.put("title", ch.getTitle());
                        chMap.put("summary", ch.getSummary());
                        chMap.put("characterNames", ch.getCharacterNames());
                        chMap.put("status", ch.getStatus() != null ? ch.getStatus() : "COMPLETED");
                        chMap.put("originalSummary", ch.getOriginalSummary());
                        return chMap;
                    })
                    .toList();
            result.put("legacyChapters", chapterList);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 把章节大纲按「所属分卷」分组。
     *
     * <p>以 {@code chapter_outlines.volume_number} 为主（逐章生成时写入的卷号，与大纲分卷一一对应），
     * <b>而不是</b> {@code volume_outlines.chapter_start/chapter_end}：那两个字段的语义已经被
     * {@link VolumeService#refreshRanges} 拿去表达<b>正文</b>（chapters）的分卷范围，而正文通常比大纲少
     * （线上真实案例：正文 6 章、大纲已经写到 30 章）。一旦按区间过滤，区间外的新章节就全部被挡在卷外 ——
     * 表现就是「库里明明有章节大纲，页面上却不显示在已有分卷下面」。
     *
     * <p>区间只作兜底：老数据的 {@code volume_number} 为空，或指向已被删掉的卷时使用。
     * 任何章节都不会被静默丢弃 —— 区间也匹配不上时挂到最后一卷并打 warn。
     *
     * @return 卷号 → 按章号升序的章节大纲（没有章节的卷不会出现在 map 里）
     */
    private Map<Integer, List<ChapterOutlineEntity>> groupChaptersByVolume(List<VolumeOutlineEntity> volumes,
                                                                          List<ChapterOutlineEntity> allChapters) {
        Map<Integer, List<ChapterOutlineEntity>> grouped = new LinkedHashMap<>();
        if (volumes.isEmpty()) {
            return grouped;
        }
        Set<Integer> volumeNumbers = new LinkedHashSet<>();
        for (VolumeOutlineEntity v : volumes) {
            volumeNumbers.add(v.getVolumeNumber());
        }
        Set<Integer> taken = new HashSet<>();

        // 1) 章节自带卷号、且该卷确实存在 —— 直接认领（权威来源）
        for (ChapterOutlineEntity ch : allChapters) {
            Integer vn = ch.getVolumeNumber();
            if (vn != null && volumeNumbers.contains(vn)) {
                grouped.computeIfAbsent(vn, k -> new ArrayList<>()).add(ch);
                taken.add(ch.getChapterNumber());
            }
        }

        // 2) 老数据没有卷号（或卷号指向已删除的卷）—— 退回区间匹配，已被认领的不重复归卷
        for (ChapterOutlineEntity ch : allChapters) {
            if (taken.contains(ch.getChapterNumber())) {
                continue;
            }
            for (VolumeOutlineEntity vol : volumes) {
                if (ch.getChapterNumber() >= vol.getChapterStart()
                        && ch.getChapterNumber() <= vol.getChapterEnd()) {
                    grouped.computeIfAbsent(vol.getVolumeNumber(), k -> new ArrayList<>()).add(ch);
                    taken.add(ch.getChapterNumber());
                    break;
                }
            }
        }

        // 3) 仍然没归上（章号超出所有卷区间）—— 挂到最后一卷，宁可多显示也不静默丢失
        int lastVolumeNumber = volumes.get(volumes.size() - 1).getVolumeNumber();
        for (ChapterOutlineEntity ch : allChapters) {
            if (taken.contains(ch.getChapterNumber())) {
                continue;
            }
            grouped.computeIfAbsent(lastVolumeNumber, k -> new ArrayList<>()).add(ch);
            taken.add(ch.getChapterNumber());
            log.warn("[P{}] 章节大纲 第{}章（volumeNumber={}）不落在任何分卷区间内，暂归到第{}卷",
                    ch.getProjectId(), ch.getChapterNumber(), ch.getVolumeNumber(), lastVolumeNumber);
        }

        grouped.values().forEach(list -> list.sort(Comparator.comparingInt(ChapterOutlineEntity::getChapterNumber)));
        return grouped;
    }

    /**
     * Edit a chapter outline
     */
    @PostMapping("/outline/edit-chapter")
    @ResponseBody
    public ResponseEntity<Map<String, String>> editChapterOutline(@PathVariable Long projectId,
                                                                    @RequestParam int chapterNumber,
                                                                    @RequestParam(required = false) String title,
                                                                    @RequestParam(required = false) String summary,
                                                                    @RequestParam(required = false) String characterNames) {
        ChapterOutlineEntity entity = chapterOutlineRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter outline not found: " + chapterNumber));
        if (title != null) entity.setTitle(title);
        if (summary != null) entity.setSummary(summary);
        if (characterNames != null) entity.setCharacterNames(characterNames);
        chapterOutlineRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Regenerate a single chapter outline via SSE
     */
    @GetMapping(value = "/outline/regenerate-chapter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter regenerateChapterOutline(@PathVariable Long projectId,
                                               @RequestParam int chapterNumber) {
        SseEmitter sealed = rejectIfSealed(projectId, "重新生成章节大纲");
        if (sealed != null) return sealed;

        long timeoutMs = globalSettingService.getAiTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);

        executor.submit(() -> {
            try {
                workflowEngine.regenerateChapterOutline(projectId, chapterNumber)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
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
                            log.error("Regenerate chapter outline error", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(error)));
                            } catch (IOException e) {
                                // ignore
                            }
                            emitter.completeWithError(error);
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("Regenerate chapter outline execution error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(e)));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Edit a volume arc
     */
    @PostMapping("/outline/edit-volume")
    @ResponseBody
    public ResponseEntity<Map<String, String>> editVolumeOutline(@PathVariable Long projectId,
                                                                   @RequestParam int volumeNumber,
                                                                   @RequestParam(required = false) String arcSummary) {
        VolumeOutlineEntity entity = volumeOutlineRepository
                .findByProjectIdAndVolumeNumber(projectId, volumeNumber)
                .orElseThrow(() -> new IllegalArgumentException("Volume outline not found: " + volumeNumber));
        if (arcSummary != null) entity.setArcSummary(arcSummary);
        volumeOutlineRepository.save(entity);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Edit story summary
     */
    @PostMapping("/outline/edit-summary")
    @ResponseBody
    public ResponseEntity<Map<String, String>> editStorySummary(@PathVariable Long projectId,
                                                                 @RequestParam String content) {
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // === Proofreading Endpoints ===

    /**
     * Get all proofreading reports for a project
     */
    @GetMapping("/proofreading/data")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getProofreadingData(@PathVariable Long projectId) {
        List<ProofreadingReportEntity> reports = proofreadingReportRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ChapterEntity ch : chapters) {
            if (ch.getContent() == null || ch.getContent().isBlank()) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("chapterNumber", ch.getChapterNumber());
            map.put("title", ch.getTitle());
            map.put("proofreadStatus", ch.getProofreadStatus() != null ? ch.getProofreadStatus().name() : "NOT_STARTED");
            map.put("proofreadFixStatus", ch.getProofreadFixStatus() != null ? ch.getProofreadFixStatus().name() : "NOT_STARTED");
            map.put("plotSummary", ch.getPlotSummary());

            // Find matching report
            reports.stream()
                    .filter(r -> r.getChapterNumber() == ch.getChapterNumber())
                    .findFirst()
                    .ifPresent(r -> {
                        map.put("characterIssues", r.getCharacterIssues());
                        map.put("consistencyIssues", r.getConsistencyIssues());
                        map.put("continuityIssues", r.getContinuityIssues());
                        map.put("foreshadowing", r.getForeshadowing());
                    });

            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get single chapter proofreading report
     */
    @GetMapping("/proofreading/{chapterNumber}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProofreadingReport(@PathVariable Long projectId,
                                                                       @PathVariable int chapterNumber) {
        return proofreadingReportRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(r -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("chapterNumber", r.getChapterNumber());
                    map.put("plotSummary", r.getPlotSummary());
                    map.put("characterIssues", r.getCharacterIssues());
                    map.put("consistencyIssues", r.getConsistencyIssues());
                    map.put("continuityIssues", r.getContinuityIssues());
                    map.put("foreshadowing", r.getForeshadowing());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Global find/replace across all project data (SSE progress)
     *
     * <p>★「替换为」留空 = <b>删除</b>左边匹配到的内容（2026-10-23 用户要求）。
     *
     * <p>参数用 {@link jakarta.servlet.http.HttpServletRequest#getParameterValues} 读**原始值**，
     * 不再用 {@code @RequestParam List<String>}：Spring 的「字符串 → 集合」转换器会把**空值转成空集合**
     * （`?newTexts=` 得到 size=0），后果不只是「这一对丢了」，而是后面所有值**前移错位** ——
     * 例如三对里第一对「替换为」留空，第二对的值就被用到了第一对上，且尾对静默消失。
     *
     * <p>逐项归一：{@code null} → 空字符串；{@code newTexts} 比 {@code oldTexts} 短 → 缺的补空串
     * （即「删除」）；{@code oldTexts} 为空/纯空白的项跳过，不参与替换。
     */
    @GetMapping(value = "/global-replace", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter globalReplace(@PathVariable Long projectId, HttpServletRequest request) {
        /* 全局替换会批量改正文 / 设定 / 角色卡 ⇒ 同样算「改文本的流程」 */
        SseEmitter sealed = rejectIfSealed(projectId, "执行全局替换");
        if (sealed != null) return sealed;

        /* 参数必须在请求线程里读完再进异步线程（request 会被容器回收），顺手做归一 */
        String[] rawOlds = request.getParameterValues("oldTexts");
        String[] rawNews = request.getParameterValues("newTexts");
        List<String> olds = new ArrayList<>();
        List<String> news = new ArrayList<>();
        if (rawOlds != null) {
            for (int i = 0; i < rawOlds.length; i++) {
                String oldText = rawOlds[i];
                if (oldText == null || oldText.isBlank()) continue;   // 左边为空 ⇒ 这对无效
                String newText = (rawNews != null && i < rawNews.length) ? rawNews[i] : null;
                olds.add(oldText);
                news.add(newText == null ? "" : newText);             // null 当空串 ⇒ 删除
            }
        }

        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        executor.submit(() -> {
            try {
                if (olds.isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("没有有效的替换对"));
                    emitter.complete();
                    return;
                }

                // Helper: apply all replacements to a string（replace 是字面量替换，newText 为 "" 即删除）
                java.util.function.Function<String, String> applyReplace = (text) -> {
                    if (text == null) return null;
                    String result = text;
                    for (int i = 0; i < olds.size(); i++) {
                        result = result.replace(olds.get(i), news.get(i));
                    }
                    return result;
                };

                // 1. Replace in world setting
                worldSettingRepository.findByProjectId(projectId).ifPresent(ws -> {
                    ws.setContent(applyReplace.apply(ws.getContent()));
                    ws.setSummary(applyReplace.apply(ws.getSummary()));
                    worldSettingRepository.save(ws);
                });
                emitter.send(SseEmitter.event().name("progress").data("世界观设定替换完成"));

                // 2. Replace in characters
                List<CharacterEntity> characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);
                for (CharacterEntity ch : characters) {
                    ch.setName(applyReplace.apply(ch.getName()));
                    ch.setContent(applyReplace.apply(ch.getContent()));
                    ch.setDescription(applyReplace.apply(ch.getDescription()));
                    ch.setPersonality(applyReplace.apply(ch.getPersonality()));
                    ch.setAppearance(applyReplace.apply(ch.getAppearance()));
                    ch.setBackground(applyReplace.apply(ch.getBackground()));
                    ch.setMotivation(applyReplace.apply(ch.getMotivation()));
                    ch.setRelationships(applyReplace.apply(ch.getRelationships()));
                    ch.setAbilities(applyReplace.apply(ch.getAbilities()));
                    characterRepository.save(ch);
                }
                emitter.send(SseEmitter.event().name("progress").data("角色设计替换完成 (" + characters.size() + "个角色)"));

                // 3. Replace in story outline
                storyOutlineRepository.findByProjectId(projectId).ifPresent(so -> {
                    so.setContent(applyReplace.apply(so.getContent()));
                    storyOutlineRepository.save(so);
                });
                emitter.send(SseEmitter.event().name("progress").data("故事大纲替换完成"));

                // 4. Replace in volume outlines
                List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
                for (VolumeOutlineEntity vol : volumes) {
                    vol.setTitle(applyReplace.apply(vol.getTitle()));
                    vol.setArcSummary(applyReplace.apply(vol.getArcSummary()));
                    volumeOutlineRepository.save(vol);
                }
                emitter.send(SseEmitter.event().name("progress").data("卷大纲替换完成 (" + volumes.size() + "卷)"));

                // 5. Replace in chapter outlines
                List<ChapterOutlineEntity> chapterOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ChapterOutlineEntity co : chapterOutlines) {
                    co.setTitle(applyReplace.apply(co.getTitle()));
                    co.setSummary(applyReplace.apply(co.getSummary()));
                    co.setCharacterNames(applyReplace.apply(co.getCharacterNames()));
                    chapterOutlineRepository.save(co);
                }
                emitter.send(SseEmitter.event().name("progress").data("分章大纲替换完成 (" + chapterOutlines.size() + "章)"));

                // 6. Replace in chapters (including derived fields)
                List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ChapterEntity ch : chapters) {
                    ch.setTitle(applyReplace.apply(ch.getTitle()));
                    ch.setContent(applyReplace.apply(ch.getContent()));
                    ch.setContentDraft(applyReplace.apply(ch.getContentDraft()));
                    ch.setPlotSummary(applyReplace.apply(ch.getPlotSummary()));
                    ch.setCharacterStates(applyReplace.apply(ch.getCharacterStates()));
                    ch.setContentBeforeFix(applyReplace.apply(ch.getContentBeforeFix()));
                    // Update word count
                    if (ch.getContent() != null) {
                        ch.setWordCount(ch.getContent().length());
                    }
                    chapterRepository.save(ch);
                }
                emitter.send(SseEmitter.event().name("progress").data("章节内容替换完成 (" + chapters.size() + "章)"));

                // 7. Replace in proofreading reports
                List<ProofreadingReportEntity> reports = proofreadingReportRepository.findByProjectIdOrderByChapterNumber(projectId);
                for (ProofreadingReportEntity report : reports) {
                    report.setPlotSummary(applyReplace.apply(report.getPlotSummary()));
                    report.setCharacterIssues(applyReplace.apply(report.getCharacterIssues()));
                    report.setConsistencyIssues(applyReplace.apply(report.getConsistencyIssues()));
                    report.setContinuityIssues(applyReplace.apply(report.getContinuityIssues()));
                    report.setForeshadowing(applyReplace.apply(report.getForeshadowing()));
                    proofreadingReportRepository.save(report);
                }
                emitter.send(SseEmitter.event().name("progress").data("校对报告替换完成 (" + reports.size() + "份)"));

                // Done
                emitter.send(SseEmitter.event().name("done").data("全局替换完成"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Global replace error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("替换出错: " + SseErrorHelper.sanitize(e)));
                } catch (IOException ex) { /* ignore */ }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/character-state-dims")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getCharacterStateDims(@PathVariable Long projectId) {
        var dimEntities = characterStateDimensionService.ensureAndGet(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var dimEntity : dimEntities) {
            Map<String, Object> dimMap = new LinkedHashMap<>();
            dimMap.put("key", dimEntity.getDimKey().name());
            dimMap.put("displayName", dimEntity.getDimKey().getDisplayName());
            dimMap.put("enabled", dimEntity.isEnabled());
            dimMap.put("defaultEnabled", dimEntity.getDimKey().isDefaultEnabled());
            result.add(dimMap);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/character-state-dims")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleCharacterStateDim(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body) {
        String dimKey = (String) body.get("dimKey");
        Boolean enabled = (Boolean) body.get("enabled");
        if (dimKey == null || enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dimKey and enabled are required"));
        }
        try {
            CharacterStateDimension dim = CharacterStateDimension.valueOf(dimKey);
            characterStateDimensionService.setEnabled(projectId, dim, enabled);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid dimension key: " + dimKey));
        }
    }

    private List<String> getSubStepsForStrategy(String strategy) {
        return List.of(
                "CHARACTER_REFINE",
                "CHAPTER_OUTLINE_REFINE",
                "CONTEXT_BRIEFING",
                "CHARACTER_STATES",
                "PROOFREAD_FIX"
        );
    }

    @PostMapping("/world-facets/regenerate")
    @ResponseBody
    public ResponseEntity<Map<String, String>> regenerateWorldFacets(@PathVariable Long projectId) {
        String deny = sealedReason(projectId, "重新展开世界观分面");
        if (deny != null) {
            return ResponseEntity.badRequest().body(Map.of("error", deny));
        }
        var ws = worldSettingRepository.findByProjectId(projectId).orElse(null);
        if (ws == null || ws.getContent() == null || ws.getContent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "世界观设定为空，无法生成分面"));
        }
        worldFacetElaborationService.elaborateAllFacetsAsync(projectId, ws.getContent());
        return ResponseEntity.ok(Map.of("message", "分面展开已在后台启动"));
    }
}
