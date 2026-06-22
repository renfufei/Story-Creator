package com.storycreator.workflow.engine;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.background.BackgroundGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class WorkflowStateService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStateService.class);

    private final WorkflowStateRepository workflowStateRepository;
    private final ProjectRepository projectRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final ChapterRepository chapterRepository;
    private final BackgroundGenerationService backgroundGenerationService;
    private final ContextSummaryService contextSummaryService;

    public WorkflowStateService(WorkflowStateRepository workflowStateRepository,
                                ProjectRepository projectRepository,
                                WorldSettingRepository worldSettingRepository,
                                CharacterRepository characterRepository,
                                StoryOutlineRepository storyOutlineRepository,
                                ChapterOutlineRepository chapterOutlineRepository,
                                VolumeOutlineRepository volumeOutlineRepository,
                                ChapterRepository chapterRepository,
                                @Lazy BackgroundGenerationService backgroundGenerationService,
                                ContextSummaryService contextSummaryService) {
        this.workflowStateRepository = workflowStateRepository;
        this.projectRepository = projectRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.chapterRepository = chapterRepository;
        this.backgroundGenerationService = backgroundGenerationService;
        this.contextSummaryService = contextSummaryService;
    }

    @Transactional
    public void saveGeneratedContent(Long projectId, WorkflowStep step, String content) {
        saveGeneratedContent(projectId, step, content, 0);
    }

    @Transactional
    public void saveGeneratedContent(Long projectId, WorkflowStep step, String content, int chapterNumber) {
        content = stripAiFormatting(content);
        log.info("[P{}] saveGeneratedContent step={} chapter={} contentLen={}", projectId, step, chapterNumber,
                content != null ? content.length() : 0);
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    return s;
                });
        // For incrementally-saved steps, don't store the huge raw stream in workflow_state
        if (step == WorkflowStep.OUTLINE_GENERATION || step == WorkflowStep.CHARACTER_DESIGN) {
            state.setGeneratedContent("[data saved incrementally]");
        } else {
            state.setGeneratedContent(content);
        }
        state.setStatus(StepStatus.GENERATED);
        workflowStateRepository.save(state);

        // Also save to specific tables
        switch (step) {
            case WORLD_BUILDING -> saveWorldSetting(projectId, content);
            case CHARACTER_DESIGN -> {
                // Characters are saved per-card in generateCharactersByCards()
            }
            case OUTLINE_GENERATION -> {
                // Outline data is saved incrementally in generateOutlineByChapters()
            }
            case CHAPTER_WRITING -> saveChapter(projectId, chapterNumber, content);
            case POLISHING -> {
                if (chapterNumber > 0) {
                    saveChapter(projectId, chapterNumber, content);
                    // Mark polish status as completed
                    chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                            .ifPresent(ch -> {
                                ch.setPolishStatus(StepStatus.CONFIRMED);
                                chapterRepository.save(ch);
                            });
                }
            }
            case PROOFREADING -> {
                // Proofreading results are saved in runProofreading() per-chapter
            }
        }
    }

    @Transactional
    public void ensureWorkflowStateExists(Long projectId, WorkflowStep step) {
        workflowStateRepository.findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    s.setStatus(StepStatus.GENERATED);
                    s.setGeneratedContent("[auto-completed]");
                    return workflowStateRepository.save(s);
                });
    }

    @Transactional
    public void confirmStep(Long projectId, WorkflowStep step) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseThrow(() -> new IllegalStateException("Step not generated yet: " + step));
        state.setStatus(StepStatus.CONFIRMED);
        workflowStateRepository.save(state);

        // Advance project to next step
        WorkflowStep nextStep = step.next();
        if (nextStep != null) {
            ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
            project.setCurrentStep(nextStep);
            projectRepository.save(project);
        }
    }

    @Transactional
    public void confirmStepOnly(Long projectId, WorkflowStep step) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseThrow(() -> new IllegalStateException("Step not generated yet: " + step));
        state.setStatus(StepStatus.CONFIRMED);
        workflowStateRepository.save(state);
    }

    @Transactional
    public void advanceStep(Long projectId, WorkflowStep step) {
        ensureWorkflowStateExists(projectId, step);
        WorkflowStep nextStep = step.next();
        if (nextStep != null) {
            ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
            project.setCurrentStep(nextStep);
            projectRepository.save(project);
        }
    }

    @Transactional
    public void saveUserEdit(Long projectId, WorkflowStep step, String editedContent) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    s.setStatus(StepStatus.GENERATED);
                    return s;
                });
        state.setUserEditedContent(editedContent);
        workflowStateRepository.save(state);

        // Update specific tables with edited content
        switch (step) {
            case WORLD_BUILDING -> saveWorldSetting(projectId, editedContent);
            case CHARACTER_DESIGN -> saveCharacterOverviewOnly(projectId, editedContent);
            case OUTLINE_GENERATION -> saveOutline(projectId, editedContent);
        }
    }

    @Transactional
    public void resetGeneratingStatus(Long projectId, WorkflowStep step, int chapterNumber) {
        // Skip reset if background task is actively running
        if (backgroundGenerationService != null && backgroundGenerationService.isActive(projectId, step, chapterNumber)) {
            log.info("[P{}] Skipping resetGeneratingStatus - bg task active step={}", projectId, step);
            return;
        }
        // Reset workflow state
        workflowStateRepository.findByProjectIdAndStep(projectId, step).ifPresent(state -> {
            if (state.getStatus() == StepStatus.GENERATING) {
                state.setStatus(state.getGeneratedContent() != null ? StepStatus.GENERATED : StepStatus.NOT_STARTED);
                workflowStateRepository.save(state);
            }
        });
        // Reset chapter status
        if (chapterNumber > 0) {
            chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).ifPresent(ch -> {
                if (ch.getStatus() == StepStatus.GENERATING) {
                    ch.setStatus(ch.getContent() != null ? StepStatus.GENERATED : StepStatus.NOT_STARTED);
                    chapterRepository.save(ch);
                }
            });
        }
    }

    public void updateStepStatus(Long projectId, WorkflowStep step, StepStatus status) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    return s;
                });
        state.setStatus(status);
        workflowStateRepository.save(state);
    }

    // --- Private persistence helpers ---

    private void saveWorldSetting(Long projectId, String content) {
        WorldSettingEntity ws = worldSettingRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    WorldSettingEntity w = new WorldSettingEntity();
                    w.setProjectId(projectId);
                    return w;
                });
        ws.setContent(content);
        String summary = contextSummaryService.summarizeWorldSetting(projectId, content);
        if (summary != null) {
            ws.setSummary(summary);
        }
        worldSettingRepository.save(ws);
    }

    void saveChapter(Long projectId, int chapterNumber, String content) {
        if (chapterNumber <= 0) return;
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return c;
                });
        chapter.setContent(content);
        chapter.setWordCount(content != null ? content.length() : 0);
        chapter.setStatus(StepStatus.GENERATED);
        // Apply outline title if chapter has no title
        if (chapter.getTitle() == null || chapter.getTitle().isBlank()) {
            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                    .map(ChapterOutlineEntity::getTitle)
                    .filter(t -> t != null && !t.isBlank())
                    .ifPresent(chapter::setTitle);
        }
        // Generate and cache content summary for next-chapter context
        if (content != null && !content.isBlank()) {
            String summary = contextSummaryService.summarizeChapterContent(projectId, chapterNumber, content);
            if (summary != null) {
                chapter.setContentSummary(summary);
            }
        }
        chapterRepository.save(chapter);
    }

    private void saveCharacterOverviewOnly(Long projectId, String content) {
        if (content == null || content.isBlank()) return;

        String overviewContent = null;
        String[] segments = content.split("\\n+\\s*-{3,}\\s*\\n+");
        for (String segment : segments) {
            if (segment.contains("## 角色总览")) {
                overviewContent = segment.replaceFirst("(?s).*?## 角色总览\\s*", "").strip();
                break;
            }
        }

        if (overviewContent == null) {
            overviewContent = content.strip();
        }

        List<CharacterEntity> all = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        CharacterEntity overview = all.stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .orElseGet(() -> {
                    CharacterEntity o = new CharacterEntity();
                    o.setProjectId(projectId);
                    o.setName("全部角色");
                    o.setSortOrder(0);
                    return o;
                });
        overview.setContent(overviewContent);
        String overviewSummary = contextSummaryService.summarizeCharacterOverview(projectId, overviewContent);
        if (overviewSummary != null) overview.setSummary(overviewSummary);
        characterRepository.save(overview);
    }

    private void saveOutline(Long projectId, String content) {
        if (content == null || content.isBlank()) return;

        if (content.contains("[[SECTION:")) {
            saveOutlineWithMarkers(projectId, content);
        } else {
            saveOutlineLegacy(projectId, content);
        }
    }

    private void saveOutlineWithMarkers(Long projectId, String content) {
        content = stripAiFormatting(content);
        volumeOutlineRepository.deleteByProjectId(projectId);
        chapterOutlineRepository.deleteByProjectId(projectId);

        Pattern sectionPattern = Pattern.compile("\\[\\[SECTION:([A-Z]+)(?::([^\\]]+))?\\]\\]");
        Pattern titlePattern = Pattern.compile("\\*\\*标题[：:]\\*\\*\\s*(.+)");
        Pattern characterPattern = Pattern.compile("\\*\\*出场角色[：:]\\*\\*\\s*(.+)");

        String[] sections = content.split("\\[\\[SECTION:");
        String storySummaryText = null;

        for (String section : sections) {
            if (section.isBlank()) continue;

            int closeBracket = section.indexOf("]]");
            if (closeBracket < 0) continue;
            String markerInfo = section.substring(0, closeBracket);
            String text = section.substring(closeBracket + 2).strip();

            String[] markerParts = markerInfo.split(":");

            if (markerParts[0].equals("VOLUME") && markerParts.length >= 4) {
                int volNum = Integer.parseInt(markerParts[1]);
                int chStart = Integer.parseInt(markerParts[2]);
                int chEnd = Integer.parseInt(markerParts[3]);

                VolumeOutlineEntity vol = new VolumeOutlineEntity();
                vol.setProjectId(projectId);
                vol.setVolumeNumber(volNum);
                vol.setChapterStart(chStart);
                vol.setChapterEnd(chEnd);
                vol.setArcSummary(text);
                vol.setTitle("第" + volNum + "卷");
                volumeOutlineRepository.save(vol);

            } else if (markerParts[0].equals("CHAPTER") && markerParts.length >= 3) {
                int chNum = Integer.parseInt(markerParts[1]);
                int volNum = Integer.parseInt(markerParts[2]);

                String title = null;
                Matcher tMatcher = titlePattern.matcher(text);
                if (tMatcher.find()) {
                    title = tMatcher.group(1).trim();
                    if (title.length() > 200) title = title.substring(0, 200);
                }

                String characterNames = null;
                Matcher cMatcher = characterPattern.matcher(text);
                if (cMatcher.find()) {
                    characterNames = cMatcher.group(1).trim();
                    if (characterNames.length() > 500) characterNames = characterNames.substring(0, 500);
                }

                String summary = text
                        .replaceFirst("\\*\\*标题[：:]\\*\\*[^\\n]*\\n?", "")
                        .replaceFirst("\\*\\*出场角色[：:]\\*\\*[^\\n]*\\n?", "")
                        .strip();

                ChapterOutlineEntity entity = new ChapterOutlineEntity();
                entity.setProjectId(projectId);
                entity.setChapterNumber(chNum);
                entity.setVolumeNumber(volNum);
                entity.setTitle(title != null ? title : "第" + chNum + "章");
                entity.setSummary(summary);
                entity.setCharacterNames(characterNames);
                entity.setStatus("COMPLETED");
                chapterOutlineRepository.save(entity);

            } else if (markerParts[0].equals("SUMMARY")) {
                storySummaryText = text;
            }
        }

        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(storySummaryText != null ? storySummaryText : "");
        storyOutlineRepository.save(outline);
    }

    private void saveOutlineLegacy(Long projectId, String content) {
        content = stripAiFormatting(content);
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);

        parseAndSaveChapterOutlinesLegacy(projectId, content);
    }

    private void parseAndSaveChapterOutlinesLegacy(Long projectId, String content) {
        if (content == null || content.isBlank()) return;

        chapterOutlineRepository.deleteByProjectId(projectId);

        String[] segments = content.split("\\n+\\s*-{3,}\\s*\\n+");
        Pattern chapterPattern = Pattern.compile("###\\s*第(\\d+)章");
        Pattern titlePattern = Pattern.compile("\\*\\*标题[：:]\\*\\*\\s*(.+)");
        Pattern characterPattern = Pattern.compile("\\*\\*出场角色[：:]\\*\\*\\s*(.+)");

        for (String segment : segments) {
            Matcher chMatcher = chapterPattern.matcher(segment);
            if (!chMatcher.find()) continue;
            int chapterNumber = Integer.parseInt(chMatcher.group(1));

            String title = null;
            Matcher tMatcher = titlePattern.matcher(segment);
            if (tMatcher.find()) {
                title = tMatcher.group(1).trim();
                if (title.length() > 200) title = title.substring(0, 200);
            }

            String characterNames = null;
            Matcher cMatcher = characterPattern.matcher(segment);
            if (cMatcher.find()) {
                characterNames = cMatcher.group(1).trim();
                if (characterNames.length() > 500) characterNames = characterNames.substring(0, 500);
            }

            String summary = segment
                    .replaceFirst("###\\s*第\\d+章[^\\n]*\\n?", "")
                    .replaceFirst("\\*\\*标题[：:]\\*\\*[^\\n]*\\n?", "")
                    .replaceFirst("\\*\\*出场角色[：:]\\*\\*[^\\n]*\\n?", "")
                    .strip();

            ChapterOutlineEntity entity = new ChapterOutlineEntity();
            entity.setProjectId(projectId);
            entity.setChapterNumber(chapterNumber);
            entity.setTitle(title != null ? title : "第" + chapterNumber + "章");
            entity.setSummary(summary);
            entity.setCharacterNames(characterNames);
            entity.setStatus("COMPLETED");
            chapterOutlineRepository.save(entity);
        }
    }
}
