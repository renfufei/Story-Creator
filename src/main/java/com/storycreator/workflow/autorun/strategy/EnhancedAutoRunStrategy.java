package com.storycreator.workflow.autorun.strategy;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.StyleFingerprintRepository;
import com.storycreator.persistence.repository.WritingRulesRepository;
import com.storycreator.workflow.autorun.AutoRunContext;
import com.storycreator.workflow.autorun.AutoRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.storycreator.workflow.engine.TextProcessingUtils.truncate;

@Component
public class EnhancedAutoRunStrategy implements AutoRunStrategy {

    private static final Logger log = LoggerFactory.getLogger(EnhancedAutoRunStrategy.class);
    private static final int CONTENT_MIN_LENGTH = 50;
    private final EnhancedSubStepExecutor executor;
    private final EnhancedChapterWritingService enhancedChapterWritingService;
    private final WritingRulesRepository writingRulesRepository;
    private final StyleFingerprintRepository styleFingerprintRepository;
    private final GlobalSettingService globalSettingService;

    public EnhancedAutoRunStrategy(EnhancedSubStepExecutor executor,
                                   EnhancedChapterWritingService enhancedChapterWritingService,
                                   WritingRulesRepository writingRulesRepository,
                                   StyleFingerprintRepository styleFingerprintRepository,
                                   GlobalSettingService globalSettingService) {
        this.executor = executor;
        this.enhancedChapterWritingService = enhancedChapterWritingService;
        this.writingRulesRepository = writingRulesRepository;
        this.styleFingerprintRepository = styleFingerprintRepository;
        this.globalSettingService = globalSettingService;
    }

    @Override
    public String getName() {
        return "ENHANCED";
    }

    @Override
    public void execute(AutoRunContext ctx) throws Exception {
        Long projectId = ctx.getProjectId();
        ProjectEntity project = ctx.getProjectRepository().findById(projectId).orElseThrow();
        WorkflowStep currentStep = project.getCurrentStep();

        // Rewind to the earliest enabled step whose content is incomplete
        WorkflowStep rewindTo = null;
        WorkflowStep scan = WorkflowStep.values()[0];
        while (scan != null && scan.ordinal() < currentStep.ordinal()) {
            if (ctx.isStepEnabled(scan) && !ctx.isStepContentComplete(scan)) {
                rewindTo = scan;
                break;
            }
            scan = scan.next();
        }
        if (rewindTo != null) {
            log.info("[P{}][ENHANCED] Rewinding from {} to {} (content incomplete)", projectId, currentStep, rewindTo);
            currentStep = rewindTo;
            project.setCurrentStep(currentStep);
            ctx.getProjectRepository().save(project);
        }

        // Iterate through workflow steps
        WorkflowStep step = currentStep;
        while (step != null) {
            if (ctx.shouldStop()) return;

            if (!ctx.isStepEnabled(step)) {
                log.info("[P{}][ENHANCED] Step {} skipped (disabled)", projectId, step);
                ctx.updateProgress(step.getDisplayName(), 0, step.getDisplayName() + " 已跳过（未启用）");
                step = step.next();
                continue;
            }

            if (ctx.isStepContentComplete(step)) {
                log.info("[P{}][ENHANCED] Step {} content already complete, skipping", projectId, step);
                ctx.updateProgress(step.getDisplayName(), 0, step.getDisplayName() + " 内容已完整，跳过");
                ctx.getWorkflowEngine().ensureWorkflowStateExists(projectId, step);
                ctx.getWorkflowEngine().confirmStep(projectId, step);
                step = step.next();
                continue;
            }

            ctx.updateProgress(step.getDisplayName(), 0, "精品模式: " + step.getDisplayName() + "...");

            switch (step) {
                case CHARACTER_DESIGN -> {
                    runPreparation(ctx);
                    if (ctx.shouldStop()) return;
                    runCharacterDesign(ctx);
                }
                case OUTLINE_GENERATION -> runOutlineGeneration(ctx);
                case CHAPTER_WRITING -> {
                    runChapterWritingEnhanced(ctx);
                    if (ctx.shouldStop()) return;
                    ctx.updateProgress("生成标题", 0, "精品模式: 正在生成章节标题...");
                    runGenerateTitles(ctx);
                }
                case POLISHING -> runPolishingEnhanced(ctx);
                case PROOFREADING -> runProofreadingAuto(ctx);
                default -> ctx.generateAndSave(step, 0);
            }

            if (ctx.shouldStop()) return;

            // Confirm step and advance
            ctx.getWorkflowEngine().ensureWorkflowStateExists(projectId, step);
            log.info("[P{}][ENHANCED] Step {} completed, confirming and advancing", projectId, step);
            ctx.updateProgress(step.getDisplayName(), 0, step.getDisplayName() + " 完成，正在推进...");
            ctx.getWorkflowEngine().confirmStep(projectId, step);

            step = step.next();
        }

        // All steps completed
        ProjectEntity p = ctx.getProjectRepository().findById(projectId).orElseThrow();
        p.setAutoRunStatus(AutoRunStatus.COMPLETED);
        p.setAutoRunProgress("精品模式创作完成！");
        p.setAutoRunStep(null);
        ctx.getProjectRepository().save(p);
        log.info("[P{}][ENHANCED] Auto run completed", projectId);
    }

    // ═══════ Phase 0: Preparation ═══════

    private void runPreparation(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        ProjectEntity project = ctx.getProjectRepository().findById(projectId).orElseThrow();
        Genre genre = project.getGenre();
        String title = project.getTitle() != null ? project.getTitle() : "";
        String genreDisplay = genre != null ? genre.getDisplayName() : "";
        String description = project.getDescription() != null ? project.getDescription() : "";

        // Load world setting (may not exist yet)
        String worldSetting = ctx.getWorldSettingRepository().findByProjectId(projectId)
                .map(WorldSettingEntity::getContent)
                .orElse("");

        // Generate writing rules if not exists
        if (writingRulesRepository.findByProjectId(projectId).isEmpty()) {
            if (ctx.shouldStop()) return;
            ctx.updateProgress(null, 0, "精品模式: 生成写作规则...");
            log.info("[P{}][ENHANCED] Generating writing rules", projectId);
            ctx.emitSubStep("WRITING_RULES", 0);

            Map<String, String> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("genre", genreDisplay);
            vars.put("description", description);
            vars.put("worldSetting", worldSetting);
            vars.put("stepGuidance", "");

            String result = executor.generateWritingRules(projectId, vars, genre, ctx::forwardTokenToObservation);
            WritingRulesEntity entity = new WritingRulesEntity();
            entity.setProjectId(projectId);
            entity.setContent(result);
            writingRulesRepository.save(entity);
        }

        // Generate style fingerprint if not exists
        if (styleFingerprintRepository.findByProjectId(projectId).isEmpty()) {
            if (ctx.shouldStop()) return;
            ctx.updateProgress(null, 0, "精品模式: 提取风格指纹...");
            log.info("[P{}][ENHANCED] Generating style fingerprint", projectId);
            ctx.emitSubStep("STYLE_FINGERPRINT", 0);

            Map<String, String> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("genre", genreDisplay);
            vars.put("description", description);
            vars.put("worldSetting", worldSetting);
            vars.put("stepGuidance", "");

            String result = executor.generateStyleFingerprint(projectId, vars, genre, ctx::forwardTokenToObservation);
            StyleFingerprintEntity entity = new StyleFingerprintEntity();
            entity.setProjectId(projectId);
            entity.setContent(result);
            styleFingerprintRepository.save(entity);
        }
    }

    // ═══════ CHARACTER_DESIGN ═══════

    private void runCharacterDesign(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        ProjectEntity project = ctx.getProjectRepository().findById(projectId).orElseThrow();
        Genre genre = project.getGenre();

        // Standard logic: generate character cards if needed
        List<CharacterEntity> existingCards = ctx.getCharacterRepository()
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        long validCards = existingCards.stream()
                .filter(c -> c.getContent() != null && c.getContent().length() > CONTENT_MIN_LENGTH)
                .count();
        if (validCards < project.getCharacterCount()) {
            ctx.generateAndSave(WorkflowStep.CHARACTER_DESIGN, 0);
        }
        if (ctx.shouldStop()) return;

        // Refine characters
        runCharacterRefine(ctx);
        if (ctx.shouldStop()) return;

        // Enhanced: generate behavior boundaries for refined characters
        String worldSetting = ctx.getWorldSettingRepository().findByProjectId(projectId)
                .map(WorldSettingEntity::getContent)
                .orElse("");
        String title = project.getTitle() != null ? project.getTitle() : "";
        String genreDisplay = genre != null ? genre.getDisplayName() : "";

        List<CharacterEntity> cards = ctx.getCharacterRepository()
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        for (CharacterEntity character : cards) {
            if (ctx.shouldStop()) return;
            if ("REFINED".equals(character.getStatus())
                    && (character.getBehaviorBoundaries() == null || character.getBehaviorBoundaries().isBlank())) {
                ctx.updateProgress(WorkflowStep.CHARACTER_DESIGN.getDisplayName(), 0,
                        "精品模式: 生成行为边界 - " + character.getName());
                log.info("[P{}][ENHANCED] Generating behavior boundaries for: {}", projectId, character.getName());
                ctx.emitSubStep("BEHAVIOR_BOUNDARIES", 0);

                Map<String, String> vars = new HashMap<>();
                vars.put("title", title);
                vars.put("genre", genreDisplay);
                vars.put("characterName", character.getName());
                vars.put("cardContent", character.getContent() != null ? character.getContent() : "");
                vars.put("worldSetting", worldSetting);
                vars.put("stepGuidance", "");

                String result = executor.generateBehaviorBoundaries(projectId, vars, genre, ctx::forwardTokenToObservation);
                character.setBehaviorBoundaries(result);
                ctx.getCharacterRepository().save(character);
            }
        }
    }

    // ═══════ OUTLINE_GENERATION ═══════

    private void runOutlineGeneration(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        ProjectEntity project = ctx.getProjectRepository().findById(projectId).orElseThrow();
        Genre genre = project.getGenre();

        // Standard logic: generate outline
        ctx.generateAndSave(WorkflowStep.OUTLINE_GENERATION, 0);
        if (ctx.shouldStop()) return;

        // Enhanced: generate event plans for chapter outlines that don't have one
        String worldSetting = ctx.getWorldSettingRepository().findByProjectId(projectId)
                .map(WorldSettingEntity::getContent)
                .orElse("");
        String title = project.getTitle() != null ? project.getTitle() : "";
        String genreDisplay = genre != null ? genre.getDisplayName() : "";
        String characters = "";
        List<CharacterEntity> chars = ctx.getCharacterRepository()
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        if (!chars.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (CharacterEntity c : chars) {
                if (c.getContent() != null) {
                    sb.append(c.getName()).append(": ").append(truncate(c.getContent(), 200)).append("\n");
                }
            }
            characters = sb.toString();
        }

        String writingRules = writingRulesRepository.findByProjectId(projectId)
                .map(WritingRulesEntity::getContent).orElse("");
        String styleFingerprint = styleFingerprintRepository.findByProjectId(projectId)
                .map(StyleFingerprintEntity::getContent).orElse("");

        List<ChapterOutlineEntity> outlines = ctx.getChapterOutlineRepository()
                .findByProjectIdOrderByChapterNumber(projectId);
        for (ChapterOutlineEntity outline : outlines) {
            if (ctx.shouldStop()) return;
            if (outline.getEventPlan() == null || outline.getEventPlan().isBlank()) {
                int chNum = outline.getChapterNumber();
                ctx.updateProgress(WorkflowStep.OUTLINE_GENERATION.getDisplayName(), chNum,
                        "精品模式: 生成事件计划 第" + chNum + "章");
                log.info("[P{}][ENHANCED] Generating event plan for chapter {}", projectId, chNum);
                ctx.emitSubStep("EVENT_PLAN", chNum);

                Map<String, String> vars = new HashMap<>();
                vars.put("title", title);
                vars.put("genre", genreDisplay);
                vars.put("chapterNumber", String.valueOf(chNum));
                vars.put("chapterSummary", outline.getSummary() != null ? outline.getSummary() : "");
                vars.put("worldSetting", worldSetting);
                vars.put("characters", characters);
                vars.put("writingRules", writingRules);
                vars.put("styleFingerprint", styleFingerprint);
                vars.put("stepGuidance", "");

                String result = executor.generateEventPlan(projectId, vars, genre, ctx::forwardTokenToObservation);
                outline.setEventPlan(result);
                ctx.getChapterOutlineRepository().save(outline);
            }
        }
    }

    // ═══════ CHAPTER_WRITING (Enhanced 7-step cycle) ═══════

    private void runChapterWritingEnhanced(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        ProjectEntity project = ctx.getProjectRepository().findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();

        // Load writing rules and style fingerprint
        String writingRules = writingRulesRepository.findByProjectId(projectId)
                .map(WritingRulesEntity::getContent).orElse("");
        String styleFingerprint = styleFingerprintRepository.findByProjectId(projectId)
                .map(StyleFingerprintEntity::getContent).orElse("");

        // Find starting chapter: first with writingCycleStatus != DONE or no content
        List<ChapterEntity> chapters = ctx.getChapterRepository().findByProjectIdOrderByChapterNumber(projectId);
        int startNum = 1;
        for (ChapterEntity ch : chapters) {
            if ("DONE".equals(ch.getWritingCycleStatus())
                    && ch.getContent() != null && !ch.getContent().isBlank()) {
                startNum = ch.getChapterNumber() + 1;
            } else {
                break;
            }
        }

        for (int num = startNum; num <= totalChapters; num++) {
            if (ctx.shouldStop()) return;
            ctx.updateProgress(WorkflowStep.CHAPTER_WRITING.getDisplayName(), num,
                    "精品模式: 第 " + num + "/" + totalChapters + " 章写作循环");

            enhancedChapterWritingService.writeChapterEnhanced(
                    ctx, num, writingRules, styleFingerprint, globalSettingService.getEnhancedDeepReviewInterval());
        }
    }

    // ═══════ POLISHING (Enhanced) ═══════

    private void runPolishingEnhanced(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        String styleFingerprint = styleFingerprintRepository.findByProjectId(projectId)
                .map(StyleFingerprintEntity::getContent).orElse("");

        List<ChapterEntity> chapters = ctx.getChapterRepository().findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsPolish = chapters.stream()
                .filter(ch -> ch.getPolishStatus() != StepStatus.CONFIRMED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < needsPolish.size(); i++) {
            if (ctx.shouldStop()) return;
            ChapterEntity ch = needsPolish.get(i);
            int chNum = ch.getChapterNumber();
            String prefix = "精品润色：第 " + chNum + " 章（" + (i + 1) + "/" + needsPolish.size() + "）";
            ctx.updateProgress(WorkflowStep.POLISHING.getDisplayName(), chNum, prefix + "...");

            // Inject style context into polishNote temporarily
            String originalNote = ch.getPolishNote();
            String extraNote = buildPolishContext(ch, styleFingerprint);
            String combinedNote = extraNote;
            if (originalNote != null && !originalNote.isBlank()) {
                combinedNote = extraNote + "\n" + originalNote;
            }
            ch.setPolishNote(truncate(combinedNote, 500));
            ctx.getChapterRepository().save(ch);

            // Run polishing
            ctx.generateAndSave(WorkflowStep.POLISHING, chNum, prefix);

            // Restore original polishNote
            ch = ctx.getChapterRepository().findByProjectIdAndChapterNumber(projectId, chNum).orElse(ch);
            ch.setPolishNote(originalNote);
            ctx.getChapterRepository().save(ch);
        }
    }

    // ═══════ PROOFREADING ═══════

    private void runProofreadingAuto(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        List<ChapterEntity> chapters = ctx.getChapterRepository().findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> chaptersWithContent = chapters.stream()
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < chaptersWithContent.size(); i++) {
            if (ctx.shouldStop()) return;
            ChapterEntity ch = chaptersWithContent.get(i);
            int chNum = ch.getChapterNumber();
            String progress = "（" + (i + 1) + "/" + chaptersWithContent.size() + "）";

            // Generate proofreading report
            if (ch.getProofreadStatus() != StepStatus.CONFIRMED
                    && ch.getProofreadStatus() != StepStatus.GENERATED) {
                String prefix = "校对报告：第 " + chNum + " 章" + progress;
                ctx.updateProgress("校对-生成报告", chNum, prefix + "...");
                ctx.generateAndSave(WorkflowStep.PROOFREADING, chNum, prefix);
            }

            if (ctx.shouldStop()) return;

            // Fix based on proofreading report
            ch = ctx.getChapterRepository().findByProjectIdAndChapterNumber(projectId, chNum).orElse(ch);
            if ((ch.getProofreadStatus() == StepStatus.GENERATED || ch.getProofreadStatus() == StepStatus.CONFIRMED)
                    && ch.getProofreadFixStatus() != StepStatus.GENERATED
                    && ch.getProofreadFixStatus() != StepStatus.CONFIRMED) {
                String prefix = "校对精修：第 " + chNum + " 章" + progress;
                ctx.updateProgress("校对-精修", chNum, prefix + "...");
                ctx.proofreadFixWithProgress(chNum, "校对-精修", prefix);
            }
        }
    }

    // ═══════ Helpers ═══════

    private void runCharacterRefine(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        List<CharacterEntity> cards = ctx.getCharacterRepository()
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        boolean needsRefine = cards.stream().anyMatch(c -> !"REFINED".equals(c.getStatus()));
        if (!needsRefine) {
            log.info("[P{}][ENHANCED] All characters already refined, skipping", projectId);
            return;
        }

        ctx.updateProgress("角色精修", 0, "精品模式: 正在精修角色卡...");
        log.info("[P{}][ENHANCED] Starting character refine", projectId);

        ctx.emitSubStep("CHARACTER_REFINE", 0);
        AtomicReference<Throwable> error = new AtomicReference<>();
        var disposable = ctx.getWorkflowEngine().refineAllCharacters(projectId)
                .doOnNext(ctx::forwardTokenToObservation)
                .doOnError(error::set)
                .subscribe();

        int perStepTimeout = ctx.getGlobalSettingService().getAiTimeoutSeconds();
        long totalTimeoutMs = perStepTimeout * 8L * 1000L;
        long deadline = System.currentTimeMillis() + totalTimeoutMs;

        while (!disposable.isDisposed()) {
            if (ctx.shouldStop()) {
                disposable.dispose();
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                disposable.dispose();
                throw new RuntimeException("角色精修超时（" + (totalTimeoutMs / 1000) + "秒）");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                disposable.dispose();
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (error.get() != null) {
            throw new RuntimeException("角色精修失败: " + error.get().getMessage(), error.get());
        }
        log.info("[P{}][ENHANCED] Character refine completed", projectId);
    }

    private void runGenerateTitles(AutoRunContext ctx) {
        ctx.emitSubStep("TITLE_GENERATION", 0);
        ctx.getWorkflowEngine().generateAndSaveTitles(ctx.getProjectId(), ctx::shouldStop, ctx::forwardTokenToObservation);
    }

    private String buildPolishContext(ChapterEntity chapter, String styleFingerprint) {
        StringBuilder sb = new StringBuilder();
        if (styleFingerprint != null && !styleFingerprint.isBlank()) {
            sb.append("【风格指纹参考】").append(truncate(styleFingerprint, 200));
        }
        // Include deep review warnings if available
        if (chapter.getDeepReview() != null && !chapter.getDeepReview().isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("【深度审查提示】").append(truncate(chapter.getDeepReview(), 200));
        }
        return sb.toString();
    }
}
