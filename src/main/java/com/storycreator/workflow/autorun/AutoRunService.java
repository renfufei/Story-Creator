package com.storycreator.workflow.autorun;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AutoRunService {

    private static final Logger log = LoggerFactory.getLogger(AutoRunService.class);

    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final WorkflowEngine workflowEngine;
    private final GlobalSettingService globalSettingService;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final ConcurrentHashMap<Long, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AutoRunService(ProjectRepository projectRepository,
                          ChapterRepository chapterRepository,
                          WorkflowEngine workflowEngine,
                          GlobalSettingService globalSettingService,
                          AutoRunStepConfigRepository autoRunStepConfigRepository) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.workflowEngine = workflowEngine;
        this.globalSettingService = globalSettingService;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
    }

    public void startAutoRun(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        if (project.getAutoRunStatus() == AutoRunStatus.RUNNING) {
            throw new IllegalStateException("自动创作已在运行中");
        }

        project.setAutoRunStatus(AutoRunStatus.RUNNING);
        project.setAutoRunError(null);
        project.setAutoRunProgress("准备开始...");
        projectRepository.save(project);

        stopSignals.remove(projectId);
        executor.submit(() -> executeAutoRun(projectId));
    }

    public void stopAutoRun(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        if (project.getAutoRunStatus() != AutoRunStatus.RUNNING) {
            return;
        }

        stopSignals.put(projectId, true);
        project.setAutoRunStatus(AutoRunStatus.STOPPING);
        project.setAutoRunProgress("正在停止...");
        projectRepository.save(project);
    }

    public Map<String, Object> getStatus(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        return Map.of(
                "status", project.getAutoRunStatus().name(),
                "step", project.getAutoRunStep() != null ? project.getAutoRunStep() : "",
                "chapter", project.getAutoRunChapter(),
                "progress", project.getAutoRunProgress() != null ? project.getAutoRunProgress() : "",
                "error", project.getAutoRunError() != null ? project.getAutoRunError() : ""
        );
    }

    private void executeAutoRun(Long projectId) {
        try {
            runSteps(projectId);
        } catch (Exception e) {
            log.error("Auto run failed for project {}", projectId, e);
            markFailed(projectId, e.getMessage());
        } finally {
            stopSignals.remove(projectId);
        }
    }

    private void runSteps(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        WorkflowStep currentStep = project.getCurrentStep();

        // If current step is past CHAPTER_WRITING, check if there are incomplete chapters that need (re)generation
        if (currentStep.ordinal() > WorkflowStep.CHAPTER_WRITING.ordinal()) {
            List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
            boolean hasIncomplete = chapters.stream()
                    .anyMatch(ch -> ch.getWordCount() == 0 || ch.getContent() == null || ch.getContent().isBlank());
            if (hasIncomplete) {
                log.info("[P{}][AutoRun] Found incomplete chapters, rewinding to CHAPTER_WRITING", projectId);
                currentStep = WorkflowStep.CHAPTER_WRITING;
                project.setCurrentStep(currentStep);
                projectRepository.save(project);
            }
        }

        WorkflowStep step = currentStep;
        while (step != null) {
            if (shouldStop(projectId)) {
                markStopped(projectId);
                return;
            }

            // Check if step is disabled in auto-run config
            AutoRunStepConfigEntity stepConfig = autoRunStepConfigRepository
                    .findByProjectIdAndStep(projectId, step).orElse(null);
            if (stepConfig != null && !stepConfig.isEnabled()) {
                log.info("[P{}][AutoRun] Step {} skipped (disabled)", projectId, step);
                updateProgress(projectId, step.getDisplayName(), 0, step.getDisplayName() + " 已跳过（未启用）");
                workflowEngine.ensureWorkflowStateExists(projectId, step);
                workflowEngine.confirmStep(projectId, step);
                step = step.next();
                continue;
            }

            updateProgress(projectId, step.getDisplayName(), 0, "正在生成: " + step.getDisplayName() + "...");

            switch (step) {
                case CHAPTER_WRITING -> {
                    runChapterWriting(projectId);
                    if (shouldStop(projectId)) { markStopped(projectId); return; }
                    updateProgress(projectId, "生成标题", 0, "正在生成章节标题...");
                    runGenerateTitles(projectId);
                }
                case POLISHING -> runPolishing(projectId);
                case PROOFREADING -> runProofreadingAuto(projectId);
                default -> generateAndSave(projectId, step, 0);
            }

            if (shouldStop(projectId)) {
                markStopped(projectId);
                return;
            }

            // Confirm step and advance
            // Ensure workflow_states row exists before confirming (multi-chapter steps may skip generation if all done)
            workflowEngine.ensureWorkflowStateExists(projectId, step);
            log.info("[P{}][AutoRun] Step {} completed, confirming and advancing", projectId, step);
            updateProgress(projectId, step.getDisplayName(), 0, step.getDisplayName() + " 完成，正在推进...");
            workflowEngine.confirmStep(projectId, step);

            step = step.next();
        }

        // All steps completed
        ProjectEntity p = projectRepository.findById(projectId).orElseThrow();
        p.setAutoRunStatus(AutoRunStatus.COMPLETED);
        p.setAutoRunProgress("全自动创作完成！");
        p.setAutoRunStep(null);
        projectRepository.save(p);
        log.info("Auto run completed for project {}", projectId);
    }

    private void generateAndSave(Long projectId, WorkflowStep step, int chapter) {
        log.info("[P{}][AutoRun] generateAndSave START step={} chapter={}", projectId, step, chapter);
        long gsStart = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        Throwable[] error = {null};
        var disposable = workflowEngine.generate(projectId, step, chapter)
                .doOnNext(content::append)
                .doOnError(e -> error[0] = e)
                .subscribe();

        // Poll: wait for completion or stop signal, with step-appropriate timeout
        int perStepTimeout = globalSettingService.getAiTimeoutSeconds();
        long totalTimeoutMs = switch (step) {
            case OUTLINE_GENERATION -> perStepTimeout * 80L * 1000L;
            case CHARACTER_DESIGN -> perStepTimeout * 8L * 1000L;
            case CHAPTER_WRITING -> perStepTimeout * 10L * 1000L;
            case POLISHING -> perStepTimeout * 10L * 1000L;
            case PROOFREADING -> perStepTimeout * 30L * 1000L;
            default -> perStepTimeout * 1000L;
        };
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        while (!disposable.isDisposed()) {
            if (shouldStop(projectId)) {
                disposable.dispose();
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                disposable.dispose();
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                throw new RuntimeException("生成超时（" + (totalTimeoutMs / 1000) + "秒），步骤: " + step.getDisplayName());
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                disposable.dispose();
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (shouldStop(projectId)) {
            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            return;
        }

        // Check if generation failed (error or empty content)
        if (error[0] != null) {
            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            throw new RuntimeException("生成失败，步骤: " + step.getDisplayName()
                    + (chapter > 0 ? " 第" + chapter + "章" : "")
                    + "，原因: " + error[0].getMessage(), error[0]);
        }
        if (content.isEmpty()) {
            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            throw new RuntimeException("生成结果为空，步骤: " + step.getDisplayName()
                    + (chapter > 0 ? " 第" + chapter + "章" : ""));
        }

        workflowEngine.saveGeneratedContent(projectId, step, content.toString(), chapter);
        log.info("[P{}][AutoRun] generateAndSave DONE step={} chapter={} elapsed={}s contentLen={}",
                projectId, step, chapter, (System.currentTimeMillis() - gsStart) / 1000, content.length());
    }

    private void runChapterWriting(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        List<ChapterEntity> existing = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

        // Reset chapters that failed: wordCount=0 or content is empty/blank
        for (ChapterEntity ch : existing) {
            if (ch.getWordCount() == 0 || ch.getContent() == null || ch.getContent().isBlank()) {
                log.info("[P{}][AutoRun] Resetting incomplete chapter {} (wordCount={}, status={})",
                        projectId, ch.getChapterNumber(), ch.getWordCount(), ch.getStatus());
                ch.setContent(null);
                ch.setStatus(StepStatus.NOT_STARTED);
                chapterRepository.save(ch);
            }
        }

        // Re-fetch after reset
        existing = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);

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
            if (shouldStop(projectId)) return;
            updateProgress(projectId, WorkflowStep.CHAPTER_WRITING.getDisplayName(), num,
                    "正在生成第 " + num + "/" + totalChapters + " 章...");
            generateAndSave(projectId, WorkflowStep.CHAPTER_WRITING, num);
        }
    }

    private void runPolishing(Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsPolish = chapters.stream()
                .filter(ch -> ch.getPolishStatus() != StepStatus.CONFIRMED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < needsPolish.size(); i++) {
            if (shouldStop(projectId)) return;
            ChapterEntity ch = needsPolish.get(i);
            updateProgress(projectId, WorkflowStep.POLISHING.getDisplayName(), ch.getChapterNumber(),
                    "正在润色第 " + ch.getChapterNumber() + " 章（" + (i + 1) + "/" + needsPolish.size() + "）...");
            generateAndSave(projectId, WorkflowStep.POLISHING, ch.getChapterNumber());
        }
    }

    private void runProofreadingAuto(Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsProofread = chapters.stream()
                .filter(ch -> ch.getProofreadStatus() != StepStatus.CONFIRMED
                        && ch.getProofreadStatus() != StepStatus.GENERATED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < needsProofread.size(); i++) {
            if (shouldStop(projectId)) return;
            ChapterEntity ch = needsProofread.get(i);
            updateProgress(projectId, WorkflowStep.PROOFREADING.getDisplayName(), ch.getChapterNumber(),
                    "正在校对第 " + ch.getChapterNumber() + " 章（" + (i + 1) + "/" + needsProofread.size() + "）...");
            generateAndSave(projectId, WorkflowStep.PROOFREADING, ch.getChapterNumber());
        }

        // Phase 2: Fix based on proofreading results
        chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsFix = chapters.stream()
                .filter(ch -> ch.getProofreadStatus() == StepStatus.GENERATED || ch.getProofreadStatus() == StepStatus.CONFIRMED)
                .filter(ch -> ch.getProofreadFixStatus() != StepStatus.GENERATED && ch.getProofreadFixStatus() != StepStatus.CONFIRMED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < needsFix.size(); i++) {
            if (shouldStop(projectId)) return;
            ChapterEntity ch = needsFix.get(i);
            updateProgress(projectId, WorkflowStep.PROOFREADING.getDisplayName(), ch.getChapterNumber(),
                    "正在精修第 " + ch.getChapterNumber() + " 章（" + (i + 1) + "/" + needsFix.size() + "）...");
            workflowEngine.proofreadFixSingleChapterSync(projectId, ch.getChapterNumber());
        }
    }

    private void runGenerateTitles(Long projectId) {
        workflowEngine.generateAndSaveTitles(projectId, () -> shouldStop(projectId));
    }

    private boolean shouldStop(Long projectId) {
        return Boolean.TRUE.equals(stopSignals.get(projectId));
    }

    private void markStopped(Long projectId) {
        stopSignals.remove(projectId);
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setAutoRunStatus(AutoRunStatus.IDLE);
        project.setAutoRunProgress("已停止");
        project.setAutoRunStep(null);
        projectRepository.save(project);
        log.info("Auto run stopped for project {}", projectId);
    }

    private void markFailed(Long projectId, String errorMessage) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setAutoRunStatus(AutoRunStatus.FAILED);
        String error = errorMessage != null ? errorMessage : "未知错误";
        if (error.length() > 490) error = error.substring(0, 490);
        project.setAutoRunError(error);
        projectRepository.save(project);
        log.error("Auto run failed for project {}: {}", projectId, error);
    }

    private void updateProgress(Long projectId, String step, int chapter, String progress) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setAutoRunStep(step);
        project.setAutoRunChapter(chapter);
        if (progress != null && progress.length() > 195) {
            progress = progress.substring(0, 195);
        }
        project.setAutoRunProgress(progress);
        projectRepository.save(project);
    }
}
