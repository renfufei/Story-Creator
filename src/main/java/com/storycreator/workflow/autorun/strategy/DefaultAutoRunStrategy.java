package com.storycreator.workflow.autorun.strategy;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.workflow.autorun.AutoRunContext;
import com.storycreator.workflow.autorun.AutoRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DefaultAutoRunStrategy implements AutoRunStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultAutoRunStrategy.class);
    private static final int CONTENT_MIN_LENGTH = 50;

    @Override
    public String getName() {
        return "DEFAULT";
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
            boolean enabled = ctx.isStepEnabled(scan);
            if (enabled && !ctx.isStepContentComplete(scan)) {
                rewindTo = scan;
                break;
            }
            scan = scan.next();
        }
        if (rewindTo != null) {
            log.info("[P{}][AutoRun] Rewinding from {} to {} (content incomplete)", projectId, currentStep, rewindTo);
            currentStep = rewindTo;
            project.setCurrentStep(currentStep);
            ctx.getProjectRepository().save(project);
        }

        WorkflowStep step = currentStep;
        while (step != null) {
            if (ctx.shouldStop()) return;

            // Check if step is disabled
            if (!ctx.isStepEnabled(step)) {
                log.info("[P{}][AutoRun] Step {} skipped (disabled), no state modified", projectId, step);
                ctx.updateProgress(step.getDisplayName(), 0, step.getDisplayName() + " 已跳过（未启用）");
                step = step.next();
                continue;
            }

            // Check if step content is already complete
            if (ctx.isStepContentComplete(step)) {
                log.info("[P{}][AutoRun] Step {} content already complete, skipping", projectId, step);
                ctx.updateProgress(step.getDisplayName(), 0, step.getDisplayName() + " 内容已完整，跳过");
                ctx.getWorkflowEngine().ensureWorkflowStateExists(projectId, step);
                ctx.getWorkflowEngine().confirmStep(projectId, step);
                step = step.next();
                continue;
            }

            ctx.updateProgress(step.getDisplayName(), 0, "正在生成: " + step.getDisplayName() + "...");

            switch (step) {
                case CHARACTER_DESIGN -> {
                    List<CharacterEntity> existingCards = ctx.getCharacterRepository()
                            .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
                    ProjectEntity proj = ctx.getProjectRepository().findById(projectId).orElseThrow();
                    long validCards = existingCards.stream()
                            .filter(c -> c.getContent() != null && c.getContent().length() > CONTENT_MIN_LENGTH)
                            .count();
                    if (validCards < proj.getCharacterCount()) {
                        ctx.generateAndSave(step, 0);
                    }
                    if (ctx.shouldStop()) return;
                    runCharacterRefine(ctx);
                }
                case CHAPTER_WRITING -> {
                    runChapterWriting(ctx);
                    if (ctx.shouldStop()) return;
                    ctx.updateProgress("生成标题", 0, "正在生成章节标题...");
                    runGenerateTitles(ctx);
                }
                case POLISHING -> runPolishing(ctx);
                case PROOFREADING -> runProofreadingAuto(ctx);
                default -> ctx.generateAndSave(step, 0);
            }

            if (ctx.shouldStop()) return;

            // Confirm step and advance
            ctx.getWorkflowEngine().ensureWorkflowStateExists(projectId, step);
            log.info("[P{}][AutoRun] Step {} completed, confirming and advancing", projectId, step);
            ctx.updateProgress(step.getDisplayName(), 0, step.getDisplayName() + " 完成，正在推进...");
            ctx.getWorkflowEngine().confirmStep(projectId, step);

            step = step.next();
        }

        // All steps completed
        ProjectEntity p = ctx.getProjectRepository().findById(projectId).orElseThrow();
        p.setAutoRunStatus(AutoRunStatus.COMPLETED);
        p.setAutoRunProgress("全自动创作完成！");
        p.setAutoRunStep(null);
        ctx.getProjectRepository().save(p);
        log.info("Auto run completed for project {}", projectId);
    }

    private void runChapterWriting(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        ProjectEntity project = ctx.getProjectRepository().findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        List<ChapterEntity> existing = ctx.getChapterRepository().findByProjectIdOrderByChapterNumber(projectId);

        // Reset chapters that failed: wordCount=0 or content is empty/blank
        for (ChapterEntity ch : existing) {
            if (ch.getWordCount() == 0 || ch.getContent() == null || ch.getContent().isBlank()) {
                log.info("[P{}][AutoRun] Resetting incomplete chapter {} (wordCount={}, status={})",
                        projectId, ch.getChapterNumber(), ch.getWordCount(), ch.getStatus());
                ch.setContent(null);
                ch.setStatus(StepStatus.NOT_STARTED);
                ctx.getChapterRepository().save(ch);
            }
        }

        // Re-fetch after reset
        existing = ctx.getChapterRepository().findByProjectIdOrderByChapterNumber(projectId);

        // Find first chapter without content to resume from
        int startNum = 1;
        for (ChapterEntity ch : existing) {
            if (ch.getContent() != null && !ch.getContent().isBlank()) {
                startNum = ch.getChapterNumber() + 1;
            } else {
                break;
            }
        }

        for (int num = startNum; num <= totalChapters; num++) {
            if (ctx.shouldStop()) return;
            String prefix = "章节写作：第 " + num + "/" + totalChapters + " 章";
            ctx.updateProgress(WorkflowStep.CHAPTER_WRITING.getDisplayName(), num, prefix + "...");
            ctx.generateAndSave(WorkflowStep.CHAPTER_WRITING, num, prefix);
            // Generate character states after each chapter
            try {
                ctx.emitSubStep("CHARACTER_STATES", num);
                ctx.getWorkflowEngine().generateCharacterStates(projectId, num, ctx::forwardTokenToObservation);
            } catch (Exception e) {
                log.warn("[P{}][AutoRun] Failed to generate character states for ch{}: {}",
                        projectId, num, e.getMessage());
            }
        }
    }

    private void runPolishing(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        List<ChapterEntity> chapters = ctx.getChapterRepository().findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsPolish = chapters.stream()
                .filter(ch -> ch.getPolishStatus() != StepStatus.CONFIRMED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < needsPolish.size(); i++) {
            if (ctx.shouldStop()) return;
            ChapterEntity ch = needsPolish.get(i);
            String prefix = "润色：第 " + ch.getChapterNumber() + " 章（" + (i + 1) + "/" + needsPolish.size() + "）";
            ctx.updateProgress(WorkflowStep.POLISHING.getDisplayName(), ch.getChapterNumber(), prefix + "...");
            ctx.generateAndSave(WorkflowStep.POLISHING, ch.getChapterNumber(), prefix);
        }
    }

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

            // Step 1: Generate proofreading report (skip if already done)
            if (ch.getProofreadStatus() != StepStatus.CONFIRMED
                    && ch.getProofreadStatus() != StepStatus.GENERATED) {
                String prefix = "校对报告：第 " + chNum + " 章" + progress;
                ctx.updateProgress("校对-生成报告", chNum, prefix + "...");
                ctx.generateAndSave(WorkflowStep.PROOFREADING, chNum, prefix);
            }

            if (ctx.shouldStop()) return;

            // Step 2: Fix based on proofreading report (skip if already done)
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

    private void runCharacterRefine(AutoRunContext ctx) {
        Long projectId = ctx.getProjectId();
        List<CharacterEntity> cards = ctx.getCharacterRepository()
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        boolean needsRefine = cards.stream().anyMatch(c -> !"REFINED".equals(c.getStatus()));
        if (!needsRefine) {
            log.info("[P{}][AutoRun] All characters already refined, skipping", projectId);
            return;
        }

        ctx.updateProgress("角色精修", 0, "正在自动精修角色卡...");
        log.info("[P{}][AutoRun] Starting character refine", projectId);

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

        log.info("[P{}][AutoRun] Character refine completed", projectId);
        ctx.updateProgress("角色精修", 0, "角色精修完成");
    }

    private void runGenerateTitles(AutoRunContext ctx) {
        ctx.emitSubStep("TITLE_GENERATION", 0);
        ctx.getWorkflowEngine().generateAndSaveTitles(ctx.getProjectId(), ctx::shouldStop, ctx::forwardTokenToObservation);
    }
}
