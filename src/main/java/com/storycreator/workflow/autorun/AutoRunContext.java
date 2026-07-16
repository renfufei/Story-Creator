package com.storycreator.workflow.autorun;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.AutoRunStepConfigEntity;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.entity.CharacterEntity;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared infrastructure context passed to AutoRunStrategy implementations.
 * Encapsulates repositories, engine, and common operations (generateAndSave, progress, stop checks).
 */
public class AutoRunContext {

    private static final Logger log = LoggerFactory.getLogger(AutoRunContext.class);
    private static final int CONTENT_MIN_LENGTH = 50;

    private static final Pattern SENTINEL_CHAR = Pattern.compile("\\[\\[CHAR:(OVERVIEW|CARD:\\d+)]]");
    private static final Pattern SENTINEL_SECTION = Pattern.compile("\\[\\[SECTION:(VOLUME:\\d+:\\d+:\\d+|CHAPTER:\\d+:\\d+|REFINE:\\d+:\\d+|SUMMARY)]]");
    private static final Pattern SENTINEL_PROOFREAD = Pattern.compile("\\[\\[PROOFREAD:CHAPTER:\\d+:(PLOT_SUMMARY|CHARACTER_CHECK|CONSISTENCY|CONTINUITY|FORESHADOWING)]]");

    private Long projectId;
    private ProjectRepository projectRepository;
    private ChapterRepository chapterRepository;
    private ChapterOutlineRepository chapterOutlineRepository;
    private CharacterRepository characterRepository;
    private WorldSettingRepository worldSettingRepository;
    private StoryOutlineRepository storyOutlineRepository;
    private WorkflowEngine workflowEngine;
    private GlobalSettingService globalSettingService;
    private AutoRunStepConfigRepository autoRunStepConfigRepository;
    private ConcurrentHashMap<Long, Boolean> stopSignals;
    private ConcurrentHashMap<Long, AutoRunObservation> observations;

    public AutoRunContext() {}

    // --- Setters ---

    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public void setProjectRepository(ProjectRepository projectRepository) { this.projectRepository = projectRepository; }
    public void setChapterRepository(ChapterRepository chapterRepository) { this.chapterRepository = chapterRepository; }
    public void setChapterOutlineRepository(ChapterOutlineRepository chapterOutlineRepository) { this.chapterOutlineRepository = chapterOutlineRepository; }
    public void setCharacterRepository(CharacterRepository characterRepository) { this.characterRepository = characterRepository; }
    public void setWorldSettingRepository(WorldSettingRepository worldSettingRepository) { this.worldSettingRepository = worldSettingRepository; }
    public void setStoryOutlineRepository(StoryOutlineRepository storyOutlineRepository) { this.storyOutlineRepository = storyOutlineRepository; }
    public void setWorkflowEngine(WorkflowEngine workflowEngine) { this.workflowEngine = workflowEngine; }
    public void setGlobalSettingService(GlobalSettingService globalSettingService) { this.globalSettingService = globalSettingService; }
    public void setAutoRunStepConfigRepository(AutoRunStepConfigRepository autoRunStepConfigRepository) { this.autoRunStepConfigRepository = autoRunStepConfigRepository; }
    public void setStopSignals(ConcurrentHashMap<Long, Boolean> stopSignals) { this.stopSignals = stopSignals; }
    public void setObservations(ConcurrentHashMap<Long, AutoRunObservation> observations) { this.observations = observations; }

    // --- Accessors ---

    public Long getProjectId() { return projectId; }
    public ProjectRepository getProjectRepository() { return projectRepository; }
    public ChapterRepository getChapterRepository() { return chapterRepository; }
    public ChapterOutlineRepository getChapterOutlineRepository() { return chapterOutlineRepository; }
    public CharacterRepository getCharacterRepository() { return characterRepository; }
    public WorldSettingRepository getWorldSettingRepository() { return worldSettingRepository; }
    public StoryOutlineRepository getStoryOutlineRepository() { return storyOutlineRepository; }
    public WorkflowEngine getWorkflowEngine() { return workflowEngine; }
    public GlobalSettingService getGlobalSettingService() { return globalSettingService; }
    public AutoRunStepConfigRepository getAutoRunStepConfigRepository() { return autoRunStepConfigRepository; }

    // --- Stop Signal ---

    public boolean shouldStop() {
        return Boolean.TRUE.equals(stopSignals.get(projectId));
    }

    // --- Observation helpers ---

    public void emitSubStep(String stepLabel, int chapter) {
        AutoRunObservation obs = observations.get(projectId);
        if (obs != null) {
            obs.reset(stepLabel, chapter);
        }
    }

    public void forwardTokenToObservation(String token) {
        AutoRunObservation obs = observations.get(projectId);
        if (obs == null) return;

        // Check for sentinel tokens and emit sub-step changes
        String subStep = parseSentinelSubStep(token);
        if (subStep != null) {
            obs.reset(subStep, obs.getCurrentChapter());
            return;
        }

        if (obs.isActive()) {
            obs.appendToken(token);
        }
    }

    private String parseSentinelSubStep(String token) {
        Matcher m = SENTINEL_CHAR.matcher(token);
        if (m.find()) {
            String val = m.group(1);
            return val.equals("OVERVIEW") ? "CHARACTER_OVERVIEW" : "CHARACTER_CARD";
        }
        m = SENTINEL_SECTION.matcher(token);
        if (m.find()) {
            String val = m.group(1);
            if (val.equals("SUMMARY")) return "STORY_SUMMARY";
            if (val.startsWith("VOLUME")) return "VOLUME_ARC";
            if (val.startsWith("CHAPTER")) return "CHAPTER_OUTLINE";
            if (val.startsWith("REFINE")) return "CHAPTER_OUTLINE_REFINE";
        }
        m = SENTINEL_PROOFREAD.matcher(token);
        if (m.find()) {
            return "PROOFREAD_" + m.group(1);
        }
        return null;
    }

    // --- Step Config ---

    public boolean isStepEnabled(WorkflowStep step) {
        AutoRunStepConfigEntity config = autoRunStepConfigRepository
                .findByProjectIdAndStep(projectId, step.name()).orElse(null);
        return config == null || config.isEnabled();
    }

    public boolean isSubStepEnabled(String subStepName) {
        return autoRunStepConfigRepository
                .findByProjectIdAndStep(projectId, subStepName)
                .map(AutoRunStepConfigEntity::isEnabled)
                .orElse(true);
    }

    // --- Progress ---

    public void updateProgress(String step, int chapter, String progress) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setAutoRunStep(step);
        project.setAutoRunChapter(chapter);
        if (progress != null && progress.length() > 195) {
            progress = progress.substring(0, 195);
        }
        project.setAutoRunProgress(progress);
        projectRepository.save(project);
    }

    // --- Content Completeness ---

    public boolean isStepContentComplete(WorkflowStep step) {
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
                return cards.stream().allMatch(c -> "REFINED".equals(c.getStatus()));
            }
            case OUTLINE_GENERATION -> {
                var outline = storyOutlineRepository.findByProjectId(projectId).orElse(null);
                if (outline == null || outline.getContent() == null || outline.getContent().length() <= CONTENT_MIN_LENGTH) {
                    return false;
                }
                List<ChapterOutlineEntity> chapterOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
                if (chapterOutlines.size() < project.getTotalChapters()) return false;
                boolean refineEnabled = isSubStepEnabled("CHAPTER_OUTLINE_REFINE");
                if (refineEnabled) {
                    return chapterOutlines.stream().allMatch(o -> "REFINED".equals(o.getStatus()));
                } else {
                    return chapterOutlines.stream().allMatch(o ->
                        "COMPLETED".equals(o.getStatus()) || "REFINED".equals(o.getStatus()));
                }
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

    // --- Generate and Save ---

    public void generateAndSave(WorkflowStep step, int chapter) {
        generateAndSave(step, chapter, null);
    }

    public void generateAndSave(WorkflowStep step, int chapter, String progressPrefix) {
        log.info("[P{}][AutoRun] generateAndSave START step={} chapter={}", projectId, step, chapter);
        long gsStart = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        AutoRunObservation obs = observations.get(projectId);
        if (obs != null) {
            obs.reset(step.name(), chapter);
        }

        var disposable = workflowEngine.generate(projectId, step, chapter)
                .doOnNext(token -> {
                    content.append(token);
                    forwardTokenToObservation(token);
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
            if (shouldStop()) {
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
                updateOutlineProgress();
            }
            if (progressPrefix != null && ++progressPollCount % 8 == 0) {
                long elapsed = (System.currentTimeMillis() - gsStart) / 1000;
                updateProgress(step.name(), chapter, progressPrefix + " [" + elapsed + "s]...");
            }
        }

        if (shouldStop()) {
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

    // --- Proofread Fix ---

    public void proofreadFixWithProgress(int chapterNumber, String stepLabel, String progressPrefix) {
        long start = System.currentTimeMillis();
        AtomicReference<Throwable> error = new AtomicReference<>();
        emitSubStep("PROOFREAD_FIX", chapterNumber);
        var disposable = workflowEngine.proofreadFixSingleChapter(projectId, chapterNumber)
                .doOnNext(this::forwardTokenToObservation)
                .doOnError(error::set)
                .subscribe();

        int perStepTimeout = globalSettingService.getAiTimeoutSeconds();
        long deadline = System.currentTimeMillis() + perStepTimeout * 3L * 1000L;
        int pollCount = 0;

        while (!disposable.isDisposed()) {
            if (shouldStop()) {
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
                updateProgress(stepLabel, chapterNumber, progressPrefix + " [" + elapsed + "s]...");
            }
        }

        if (error.get() != null) {
            throw new RuntimeException("校对精修失败 第" + chapterNumber + "章: " + error.get().getMessage(), error.get());
        }
    }

    // --- Outline Progress ---

    public void updateOutlineProgress() {
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
            updateProgress(WorkflowStep.OUTLINE_GENERATION.name(), active.get().getChapterNumber(), msg);
        }
    }
}
