package com.storycreator.workflow.engine;

import org.springframework.beans.factory.annotation.Autowired;

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

    private WorkflowStateRepository workflowStateRepository;
    private ProjectRepository projectRepository;
    private WorldSettingRepository worldSettingRepository;
    private CharacterRepository characterRepository;
    private StoryOutlineRepository storyOutlineRepository;
    private ChapterOutlineRepository chapterOutlineRepository;
    private ChapterRepository chapterRepository;
    private BackgroundGenerationService backgroundGenerationService;
    private ContextSummaryService contextSummaryService;
    private WorldFacetElaborationService worldFacetElaborationService;

    @Autowired
    public void setWorkflowStateRepository(WorkflowStateRepository workflowStateRepository) {
        this.workflowStateRepository = workflowStateRepository;
    }

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Autowired
    public void setWorldSettingRepository(WorldSettingRepository worldSettingRepository) {
        this.worldSettingRepository = worldSettingRepository;
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
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setBackgroundGenerationService(@Lazy BackgroundGenerationService backgroundGenerationService) {
        this.backgroundGenerationService = backgroundGenerationService;
    }

    @Autowired
    public void setContextSummaryService(ContextSummaryService contextSummaryService) {
        this.contextSummaryService = contextSummaryService;
    }

    @Autowired
    public void setWorldFacetElaborationService(WorldFacetElaborationService worldFacetElaborationService) {
        this.worldFacetElaborationService = worldFacetElaborationService;
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

    /**
     * 回填工作流步骤内容（不带业务表副作用）。
     * <p>与 {@link #saveGeneratedContent} 不同，本方法会把真实内容写入 {@code generatedContent}
     * （不替换为 [data saved incrementally] 占位符），且不向各业务表重复落库。
     * 适用于逆向工程等「产出已另存业务表、此处仅回填工作流展示内容」的场景。
     */
    @Transactional
    public void saveStepContent(Long projectId, WorkflowStep step, String content) {
        if (content == null || content.isBlank()) return;
        content = stripAiFormatting(content);
        log.info("[P{}] saveStepContent step={} contentLen={}", projectId, step, content.length());
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    return s;
                });
        state.setGeneratedContent(content);
        state.setStatus(StepStatus.GENERATED);
        workflowStateRepository.save(state);
    }

    /**
     * 清空某步骤的回填内容（用于逆向工程重置进度）。
     * <p>仅清除 {@code generatedContent} 并把状态复位为 NOT_STARTED；
     * 若用户已手动编辑过（userEditedContent 非空），保留用户编辑内容不动。
     */
    @Transactional
    public void resetStepContent(Long projectId, WorkflowStep step) {
        workflowStateRepository.findByProjectIdAndStep(projectId, step).ifPresent(state -> {
            state.setGeneratedContent(null);
            if (state.getUserEditedContent() == null || state.getUserEditedContent().isBlank()) {
                state.setStatus(StepStatus.NOT_STARTED);
            }
            workflowStateRepository.save(state);
            log.info("[P{}] resetStepContent step={}", projectId, step);
        });
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
        // Asynchronously generate world facets (does not block)
        worldFacetElaborationService.elaborateAllFacetsAsync(projectId, content);
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
