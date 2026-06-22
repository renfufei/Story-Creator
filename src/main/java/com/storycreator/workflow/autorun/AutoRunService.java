package com.storycreator.workflow.autorun;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.AutoRunStepConfigRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StoryOutlineRepository;
import com.storycreator.persistence.repository.WorldSettingRepository;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AutoRunService {

    private static final Logger log = LoggerFactory.getLogger(AutoRunService.class);

    private static final int CONTENT_MIN_LENGTH = 50;

    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final CharacterRepository characterRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final WorkflowEngine workflowEngine;
    private final GlobalSettingService globalSettingService;
    private final AutoRunStepConfigRepository autoRunStepConfigRepository;
    private final ConcurrentHashMap<Long, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> runningProjects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AutoRunObservation> observations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService observationCleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    public static class AutoRunObservation {
        private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);
        private final StringBuffer tokenBuffer = new StringBuffer();
        private volatile String currentStepName = "";
        private volatile int currentChapter = 0;
        private volatile boolean active = false;

        public void reset(String step, int chapter) {
            tokenBuffer.setLength(0);
            currentStepName = step;
            currentChapter = chapter;
        }

        public Sinks.Many<String> getSink() { return sink; }
        public String getTokenBuffer() { return tokenBuffer.toString(); }
        public String getCurrentStepName() { return currentStepName; }
        public int getCurrentChapter() { return currentChapter; }
        public boolean isActive() { return active; }
    }

    public AutoRunService(ProjectRepository projectRepository,
                          ChapterRepository chapterRepository,
                          ChapterOutlineRepository chapterOutlineRepository,
                          CharacterRepository characterRepository,
                          WorldSettingRepository worldSettingRepository,
                          StoryOutlineRepository storyOutlineRepository,
                          WorkflowEngine workflowEngine,
                          GlobalSettingService globalSettingService,
                          AutoRunStepConfigRepository autoRunStepConfigRepository) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.characterRepository = characterRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.workflowEngine = workflowEngine;
        this.globalSettingService = globalSettingService;
        this.autoRunStepConfigRepository = autoRunStepConfigRepository;
    }

    public void startAutoRun(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        if (project.getStatus() == com.storycreator.core.domain.ProjectStatus.COMPLETED) {
            throw new IllegalStateException("项目状态为「已完本」，无法启动自动创作");
        }
        if (project.getStatus() == com.storycreator.core.domain.ProjectStatus.ABANDONED) {
            throw new IllegalStateException("项目状态为「已废弃」，无法启动自动创作");
        }

        // Atomic check-and-set to prevent double-start race condition
        if (runningProjects.putIfAbsent(projectId, true) != null) {
            throw new IllegalStateException("自动创作已在运行中");
        }

        project.setAutoRunStatus(AutoRunStatus.RUNNING);
        project.setAutoRunError(null);
        project.setAutoRunProgress("准备开始...");
        projectRepository.save(project);

        stopSignals.remove(projectId);

        // Create observation for real-time streaming
        AutoRunObservation obs = new AutoRunObservation();
        obs.active = true;
        observations.put(projectId, obs);

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

    public AutoRunObservation getObservation(Long projectId) {
        return observations.get(projectId);
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
            runningProjects.remove(projectId);
            stopSignals.remove(projectId);
            completeObservation(projectId);
        }
    }

    private void completeObservation(Long projectId) {
        AutoRunObservation obs = observations.get(projectId);
        if (obs != null) {
            obs.active = false;
            obs.getSink().tryEmitComplete();
            // Only remove if the observation in the map is still this same instance
            // (a restart may have replaced it with a new active observation)
            observationCleanupScheduler.schedule(() -> observations.remove(projectId, obs), 30, TimeUnit.SECONDS);
        }
    }

    private void runSteps(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        WorkflowStep currentStep = project.getCurrentStep();

        // Rewind to the earliest enabled step whose content is incomplete
        WorkflowStep rewindTo = null;
        WorkflowStep scan = WorkflowStep.values()[0];
        while (scan != null && scan.ordinal() < currentStep.ordinal()) {
            AutoRunStepConfigEntity scanConfig = autoRunStepConfigRepository
                    .findByProjectIdAndStep(projectId, scan).orElse(null);
            boolean enabled = (scanConfig == null || scanConfig.isEnabled());
            if (enabled && !isStepContentComplete(projectId, scan)) {
                rewindTo = scan;
                break;
            }
            scan = scan.next();
        }
        if (rewindTo != null) {
            log.info("[P{}][AutoRun] Rewinding from {} to {} (content incomplete)", projectId, currentStep, rewindTo);
            currentStep = rewindTo;
            project.setCurrentStep(currentStep);
            projectRepository.save(project);
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
                log.info("[P{}][AutoRun] Step {} skipped (disabled), no state modified", projectId, step);
                updateProgress(projectId, step.getDisplayName(), 0, step.getDisplayName() + " 已跳过（未启用）");
                step = step.next();
                continue;
            }

            // Check if step content is already complete — skip generation but confirm
            if (isStepContentComplete(projectId, step)) {
                log.info("[P{}][AutoRun] Step {} content already complete, skipping", projectId, step);
                updateProgress(projectId, step.getDisplayName(), 0, step.getDisplayName() + " 内容已完整，跳过");
                workflowEngine.ensureWorkflowStateExists(projectId, step);
                workflowEngine.confirmStep(projectId, step);
                step = step.next();
                continue;
            }

            updateProgress(projectId, step.getDisplayName(), 0, "正在生成: " + step.getDisplayName() + "...");

            switch (step) {
                case CHARACTER_DESIGN -> {
                    // Check if card generation is needed
                    List<CharacterEntity> existingCards = characterRepository
                            .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
                    ProjectEntity proj = projectRepository.findById(projectId).orElseThrow();
                    long validCards = existingCards.stream()
                            .filter(c -> c.getContent() != null && c.getContent().length() > CONTENT_MIN_LENGTH)
                            .count();
                    if (validCards < proj.getCharacterCount()) {
                        // Cards not yet fully generated, run generation
                        generateAndSave(projectId, step, 0);
                    }
                    if (shouldStop(projectId)) { markStopped(projectId); return; }
                    // Auto-refine characters after generation
                    runCharacterRefine(projectId);
                }
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

    private boolean isStepContentComplete(Long projectId, WorkflowStep step) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        switch (step) {
            case WORLD_BUILDING -> {
                var ws = worldSettingRepository.findByProjectId(projectId).orElse(null);
                return ws != null && ws.getContent() != null && ws.getContent().length() > CONTENT_MIN_LENGTH;
            }
            case CHARACTER_DESIGN -> {
                List<CharacterEntity> cards = characterRepository
                        .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
                long validCards = cards.stream()
                        .filter(c -> c.getContent() != null && c.getContent().length() > CONTENT_MIN_LENGTH)
                        .count();
                if (validCards < project.getCharacterCount()) return false;
                // All cards must be refined for step to be considered complete
                return cards.stream().allMatch(c -> "REFINED".equals(c.getStatus()));
            }
            case OUTLINE_GENERATION -> {
                var outline = storyOutlineRepository.findByProjectId(projectId).orElse(null);
                if (outline == null || outline.getContent() == null || outline.getContent().length() <= CONTENT_MIN_LENGTH) {
                    return false;
                }
                List<ChapterOutlineEntity> chapterOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
                if (chapterOutlines.size() < project.getTotalChapters()) return false;
                // All chapters must be refined for the step to be fully complete
                return chapterOutlines.stream().allMatch(o -> "REFINED".equals(o.getStatus()));
            }
            case CHAPTER_WRITING -> {
                List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
                if (chapters.isEmpty()) return false;
                long validChapters = chapters.stream()
                        .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank() && ch.getWordCount() > 0)
                        .count();
                return validChapters >= project.getTotalChapters();
            }
            case POLISHING -> {
                List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
                List<ChapterEntity> withContent = chapters.stream()
                        .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                        .toList();
                if (withContent.isEmpty()) return false;
                return withContent.stream().allMatch(ch -> ch.getPolishStatus() == StepStatus.CONFIRMED);
            }
            case PROOFREADING -> {
                List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
                List<ChapterEntity> withContent = chapters.stream()
                        .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                        .toList();
                if (withContent.isEmpty()) return false;
                return withContent.stream().allMatch(ch ->
                        ch.getProofreadFixStatus() == StepStatus.CONFIRMED || ch.getProofreadFixStatus() == StepStatus.GENERATED);
            }
            default -> { return false; }
        }
    }

    private void generateAndSave(Long projectId, WorkflowStep step, int chapter) {
        generateAndSave(projectId, step, chapter, null);
    }

    private void generateAndSave(Long projectId, WorkflowStep step, int chapter, String progressPrefix) {
        log.info("[P{}][AutoRun] generateAndSave START step={} chapter={}", projectId, step, chapter);
        long gsStart = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        AutoRunObservation obs = observations.get(projectId);
        if (obs != null) {
            obs.reset(step.name(), chapter);
            obs.getSink().tryEmitNext("[[AUTORUN_STEP:" + step.name() + ":" + chapter + "]]");
        }

        var disposable = workflowEngine.generate(projectId, step, chapter)
                .doOnNext(token -> {
                    content.append(token);
                    if (obs != null && obs.active) {
                        obs.tokenBuffer.append(token);
                        obs.getSink().tryEmitNext(token);
                    }
                })
                .doOnError(error::set)
                .subscribe();

        // Poll: wait for completion or stop signal, with step-appropriate timeout
        int perStepTimeout = globalSettingService.getAiTimeoutSeconds();
        long totalTimeoutMs = switch (step) {
            case OUTLINE_GENERATION -> perStepTimeout * 80L * 1000L;
            case CHARACTER_DESIGN -> perStepTimeout * 8L * 1000L;
            case CHAPTER_WRITING -> perStepTimeout * 10L * 1000L;
            case POLISHING -> perStepTimeout * 10L * 1000L;
            case PROOFREADING -> perStepTimeout * 8L * 1000L;
            default -> perStepTimeout * 1000L;
        };
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        int outlinePollCount = 0;
        int progressPollCount = 0;
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
            if (step == WorkflowStep.OUTLINE_GENERATION && ++outlinePollCount % 4 == 0) {
                updateOutlineProgress(projectId);
            }
            if (progressPrefix != null && ++progressPollCount % 8 == 0) {
                long elapsed = (System.currentTimeMillis() - gsStart) / 1000;
                updateProgress(projectId, step.getDisplayName(), chapter, progressPrefix + " [" + elapsed + "s]...");
            }
        }

        if (shouldStop(projectId)) {
            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            return;
        }

        // Check if generation failed (error or empty content)
        if (error.get() != null) {
            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
            throw new RuntimeException("生成失败，步骤: " + step.getDisplayName()
                    + (chapter > 0 ? " 第" + chapter + "章" : "")
                    + "，原因: " + error.get().getMessage(), error.get());
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
            String prefix = "章节写作：第 " + num + "/" + totalChapters + " 章";
            updateProgress(projectId, WorkflowStep.CHAPTER_WRITING.getDisplayName(), num, prefix + "...");
            generateAndSave(projectId, WorkflowStep.CHAPTER_WRITING, num, prefix);
            // 同步生成角色状态，确保不与下一章 AI 请求并发
            try {
                workflowEngine.generateCharacterStates(projectId, num);
            } catch (Exception e) {
                log.warn("[P{}][AutoRun] Failed to generate character states for ch{}: {}",
                        projectId, num, e.getMessage());
            }
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
            String prefix = "润色：第 " + ch.getChapterNumber() + " 章（" + (i + 1) + "/" + needsPolish.size() + "）";
            updateProgress(projectId, WorkflowStep.POLISHING.getDisplayName(), ch.getChapterNumber(), prefix + "...");
            generateAndSave(projectId, WorkflowStep.POLISHING, ch.getChapterNumber(), prefix);
        }
    }

    private void runProofreadingAuto(Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> chaptersWithContent = chapters.stream()
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        for (int i = 0; i < chaptersWithContent.size(); i++) {
            if (shouldStop(projectId)) return;
            ChapterEntity ch = chaptersWithContent.get(i);
            int chNum = ch.getChapterNumber();
            String progress = "（" + (i + 1) + "/" + chaptersWithContent.size() + "）";

            // Step 1: Generate proofreading report (skip if already done)
            if (ch.getProofreadStatus() != StepStatus.CONFIRMED
                    && ch.getProofreadStatus() != StepStatus.GENERATED) {
                String prefix = "校对报告：第 " + chNum + " 章" + progress;
                updateProgress(projectId, "校对-生成报告", chNum, prefix + "...");
                generateAndSave(projectId, WorkflowStep.PROOFREADING, chNum, prefix);
            }

            if (shouldStop(projectId)) return;

            // Step 2: Fix based on proofreading report (skip if already done)
            // Re-fetch chapter to get updated proofreadStatus
            ch = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).orElse(ch);
            if ((ch.getProofreadStatus() == StepStatus.GENERATED || ch.getProofreadStatus() == StepStatus.CONFIRMED)
                    && ch.getProofreadFixStatus() != StepStatus.GENERATED
                    && ch.getProofreadFixStatus() != StepStatus.CONFIRMED) {
                String prefix = "校对精修：第 " + chNum + " 章" + progress;
                updateProgress(projectId, "校对-精修", chNum, prefix + "...");
                proofreadFixWithProgress(projectId, chNum, "校对-精修", prefix);
            }
        }
    }

    private void proofreadFixWithProgress(Long projectId, int chapterNumber, String stepLabel, String progressPrefix) {
        long start = System.currentTimeMillis();
        AtomicReference<Throwable> error = new AtomicReference<>();
        var disposable = workflowEngine.proofreadFixSingleChapter(projectId, chapterNumber)
                .doOnError(error::set)
                .subscribe();

        int perStepTimeout = globalSettingService.getAiTimeoutSeconds();
        long deadline = System.currentTimeMillis() + perStepTimeout * 3L * 1000L;
        int pollCount = 0;

        while (!disposable.isDisposed()) {
            if (shouldStop(projectId)) {
                disposable.dispose();
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                disposable.dispose();
                throw new RuntimeException("校对精修超时 第" + chapterNumber + "章");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                disposable.dispose();
                Thread.currentThread().interrupt();
                return;
            }
            if (++pollCount % 8 == 0) {
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                updateProgress(projectId, stepLabel, chapterNumber, progressPrefix + " [" + elapsed + "s]...");
            }
        }

        if (error.get() != null) {
            throw new RuntimeException("校对精修失败 第" + chapterNumber + "章: " + error.get().getMessage(), error.get());
        }
    }

    private void runCharacterRefine(Long projectId) {
        // Check if any characters need refining
        List<CharacterEntity> cards = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        boolean needsRefine = cards.stream().anyMatch(c -> !"REFINED".equals(c.getStatus()));
        if (!needsRefine) {
            log.info("[P{}][AutoRun] All characters already refined, skipping", projectId);
            return;
        }

        updateProgress(projectId, "角色精修", 0, "正在自动精修角色卡...");
        log.info("[P{}][AutoRun] Starting character refine", projectId);

        AtomicReference<Throwable> error = new AtomicReference<>();
        var disposable = workflowEngine.refineAllCharacters(projectId)
                .doOnError(error::set)
                .subscribe();

        int perStepTimeout = globalSettingService.getAiTimeoutSeconds();
        long totalTimeoutMs = perStepTimeout * 8L * 1000L;
        long deadline = System.currentTimeMillis() + totalTimeoutMs;

        while (!disposable.isDisposed()) {
            if (shouldStop(projectId)) {
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
        updateProgress(projectId, "角色精修", 0, "角色精修完成");
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

    private void updateOutlineProgress(Long projectId) {
        List<ChapterOutlineEntity> outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
        if (outlines.isEmpty()) return;
        Optional<ChapterOutlineEntity> active = outlines.stream()
                .filter(o -> "GENERATING".equals(o.getStatus()) || "REFINING".equals(o.getStatus()))
                .findFirst();
        if (active.isPresent()) {
            boolean refining = "REFINING".equals(active.get().getStatus());
            String msg = refining
                    ? "正在精修第" + active.get().getChapterNumber() + "章大纲..."
                    : "正在生成第" + active.get().getChapterNumber() + "章大纲...";
            updateProgress(projectId, refining ? "大纲生成-精修中" : "大纲生成", active.get().getChapterNumber(), msg);
        }
    }
}
