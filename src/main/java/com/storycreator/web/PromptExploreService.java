package com.storycreator.web;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.image.CharacterImageService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.sidestory.SideStoryWorkflowService;
import com.storycreator.workflow.engine.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class PromptExploreService {

    private final PromptTemplateRegistry promptRegistry;
    private final WorkflowContextBuilder contextBuilder;
    private final CharacterGenerationService characterGenerationService;
    private final OutlineGenerationService outlineGenerationService;
    private final ProofreadingService proofreadingService;
    private final CharacterStateService characterStateService;
    private final CharacterImageService characterImageService;
    private final SideStoryWorkflowService sideStoryWorkflowService;
    private final ProjectRepository projectRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final ChapterRepository chapterRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final SideStoryRepository sideStoryRepository;
    private final WorldFacetElaborationService worldFacetElaborationService;

    public PromptExploreService(PromptTemplateRegistry promptRegistry,
                                WorkflowContextBuilder contextBuilder,
                                CharacterGenerationService characterGenerationService,
                                OutlineGenerationService outlineGenerationService,
                                ProofreadingService proofreadingService,
                                CharacterStateService characterStateService,
                                CharacterImageService characterImageService,
                                SideStoryWorkflowService sideStoryWorkflowService,
                                ProjectRepository projectRepository,
                                PromptTemplateRepository promptTemplateRepository,
                                WorldSettingRepository worldSettingRepository,
                                CharacterRepository characterRepository,
                                ChapterRepository chapterRepository,
                                StepGuidanceRepository stepGuidanceRepository,
                                SideStoryRepository sideStoryRepository,
                                WorldFacetElaborationService worldFacetElaborationService) {
        this.promptRegistry = promptRegistry;
        this.contextBuilder = contextBuilder;
        this.characterGenerationService = characterGenerationService;
        this.outlineGenerationService = outlineGenerationService;
        this.proofreadingService = proofreadingService;
        this.characterStateService = characterStateService;
        this.characterImageService = characterImageService;
        this.sideStoryWorkflowService = sideStoryWorkflowService;
        this.projectRepository = projectRepository;
        this.promptTemplateRepository = promptTemplateRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.chapterRepository = chapterRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.sideStoryRepository = sideStoryRepository;
        this.worldFacetElaborationService = worldFacetElaborationService;
    }

    public record ExploreResult(String templateContent, String systemPrompt,
                                Map<String, String> variables, String renderedPrompt) {}

    /**
     * Resolve variables and render prompt for a given step/subStep and project context.
     */
    public ExploreResult resolve(WorkflowStep step, PromptSubStep subStep, PromptExploreContext ctx) {
        Long projectId = ctx.getProjectId();

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        Map<String, String> variables;
        String templateContent;
        String systemPrompt;

        if (subStep != null && subStep.isPrimary()) {
            // _PRIMARY sub-steps use main-step variable building and template resolution
            subStep = null;
        }

        if (subStep != null) {
            // Sub-step templates
            variables = buildSubStepVariables(subStep, project, ctx);
            templateContent = promptRegistry.getSubStepTemplate(step, subStep, project.getGenre());
            systemPrompt = promptRegistry.getSubStepSystemPrompt(step, subStep, project.getGenre());
        } else {
            // Main step templates (WORLD_BUILDING, CHAPTER_WRITING, POLISHING)
            Integer chapterNumber = ctx.getChapterNumber();
            int chNum = chapterNumber != null ? chapterNumber : 0;
            WorkflowContext wfCtx = contextBuilder.build(projectId, chNum);
            variables = wfCtx.toTemplateVariables();
            templateContent = promptRegistry.getTemplate(step, project.getGenre());
            systemPrompt = promptRegistry.getSystemPrompt(step, project.getGenre());

            // Fix: override stepGuidance with the explored template's step, not project.currentStep
            String correctGuidance = loadStepGuidance(projectId, step);
            variables.put("stepGuidance", correctGuidance);
        }

        // If a specific custom templateId is provided, override template content and system prompt
        Long templateId = ctx.getTemplateId();
        if (templateId != null) {
            Optional<PromptTemplateEntity> customTemplate = promptTemplateRepository.findById(templateId);
            if (customTemplate.isPresent()) {
                PromptTemplateEntity entity = customTemplate.get();
                templateContent = entity.getTemplate() != null ? entity.getTemplate() : templateContent;
                systemPrompt = entity.getSystemPrompt() != null ? entity.getSystemPrompt() : systemPrompt;
            }
        }

        String renderedPrompt = promptRegistry.resolveTemplate(templateContent, variables);
        return new ExploreResult(templateContent, systemPrompt, variables, renderedPrompt);
    }

    private Map<String, String> buildSubStepVariables(PromptSubStep subStep, ProjectEntity project,
                                                       PromptExploreContext ctx) {
        Long projectId = ctx.getProjectId();
        Integer chapterNumber = ctx.getChapterNumber();
        Long characterId = ctx.getCharacterId();
        Integer cardNumber = ctx.getCardNumber();
        Integer totalCards = ctx.getTotalCards();
        Integer volumeNumber = ctx.getVolumeNumber();
        return switch (subStep) {
            case CHARACTER_CARD -> characterGenerationService.buildCharacterCardVariables(
                    projectId, cardNumber != null ? cardNumber : 1, totalCards != null ? totalCards : 5);
            case CHARACTER_OVERVIEW -> characterGenerationService.buildCharacterOverviewVariables(projectId);
            case CHARACTER_REFINE -> characterGenerationService.buildCharacterRefineVariables(projectId, characterId);
            case VOLUME_ARC -> outlineGenerationService.buildVolumeArcVariables(
                    projectId, volumeNumber != null ? volumeNumber : 1);
            case VOLUME_CHARACTERS -> outlineGenerationService.buildVolumeCharactersVariables(
                    projectId, volumeNumber != null ? volumeNumber : 1);
            case CHAPTER_OUTLINE -> outlineGenerationService.buildChapterOutlineVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case CHAPTER_OUTLINE_REFINE -> outlineGenerationService.buildChapterOutlineRefineVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case STORY_SUMMARY -> outlineGenerationService.buildStorySummaryVariables(projectId);
            case PROOFREAD_PLOT_SUMMARY -> proofreadingService.buildProofreadPlotSummaryVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_FORESHADOWING -> proofreadingService.buildProofreadForeshadowingVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_FIX -> proofreadingService.buildProofreadFixVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case CHARACTER_STATES -> characterStateService.buildCharacterStateVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case IMAGE_PROMPT_AVATAR -> characterImageService.buildImagePromptVariables(
                    projectId, characterId, ImageType.AVATAR);
            case IMAGE_PROMPT_PORTRAIT -> characterImageService.buildImagePromptVariables(
                    projectId, characterId, ImageType.PORTRAIT);
            case CHAPTER_CONTEXT_BRIEFING -> buildChapterContextBriefingVariables(projectId, chapterNumber);
            case SIDE_STORY_OUTLINE -> buildSideStoryOutlineVariables(project, ctx);
            case SIDE_STORY_CHAPTER_OUTLINE -> buildSideStoryChapterOutlineVariables(project, ctx);
            case SIDE_STORY_WRITING -> buildSideStoryWritingVariables(project, ctx);
            case CHAPTER_EXPANSION -> buildChapterExpansionVariables(projectId, chapterNumber, project);
            case REVERSE_WORLD_BUILDING, REVERSE_CHARACTER_EXTRACTION, REVERSE_OUTLINE_GENERATION ->
                    Map.of("title", safe(project.getTitle()),
                            "genre", project.getGenre() != null ? project.getGenre().getDisplayName() : "",
                            "sampledChapters", "(导入时自动填充)",
                            "chapterCount", String.valueOf(project.getTotalChapters()),
                            "worldSetting", "(导入时自动填充)",
                            "characters", "(导入时自动填充)");
            case WORLD_BUILDING_PRIMARY, CHAPTER_WRITING_PRIMARY, POLISHING_PRIMARY ->
                    throw new IllegalStateException("PRIMARY sub-steps should be intercepted before reaching switch");
        };
    }

    // ═══════ Context Briefing variable builder ═══════

    private Map<String, String> buildChapterContextBriefingVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        // Use contextBuilder to get standard chapter context (characterCards, previousChapterContent, etc.)
        WorkflowContext wfCtx = contextBuilder.build(projectId, chNum);

        String chapterSummary = wfCtx.getChapterSummary() != null ? wfCtx.getChapterSummary() : "";
        String chapterCards = wfCtx.getCharacterCards() != null ? wfCtx.getCharacterCards() : "";
        String prevContent = wfCtx.getPreviousChapterContent() != null ? wfCtx.getPreviousChapterContent() : "";

        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(wfCtx.getTitle()));
        vars.put("genre", wfCtx.getGenre() != null ? wfCtx.getGenre().getDisplayName() : "");
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("previousChapterContent", prevContent);
        vars.put("chapterSummary", chapterSummary);
        vars.put("writingRules", "");
        vars.put("characterCards", chapterCards);
        vars.put("stepGuidance", loadRawStepGuidance(projectId, WorkflowStep.CHAPTER_WRITING));
        return vars;
    }

    // ═══════ Expansion variable builders ═══════

    private Map<String, String> buildChapterExpansionVariables(Long projectId, Integer chapterNumber, ProjectEntity project) {
        int chNum = chapterNumber != null ? chapterNumber : 1;
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chNum));
        String content = chapter.getContent() != null ? chapter.getContent() : "";
        String guidanceText = project.getExpansionGuidance();
        String guidanceSection = (guidanceText != null && !guidanceText.isBlank())
                ? "【扩写方向】\n" + guidanceText : "";
        return Map.of(
                "title", project.getTitle(),
                "genre", project.getGenre() != null ? project.getGenre().name() : "",
                "chapterNumber", String.valueOf(chNum),
                "chapterTitle", chapter.getTitle() != null ? chapter.getTitle() : "",
                "originalContent", content,
                "expansionGuidance", guidanceSection,
                "chapterWordCount", String.valueOf(content.length())
        );
    }

    // ═══════ Side story variable builders ═══════

    private Map<String, String> buildSideStoryOutlineVariables(ProjectEntity project, PromptExploreContext ctx) {
        Long sideStoryId = ctx.getSideStoryId();
        if (sideStoryId == null) {
            throw new IllegalArgumentException("请选择番外篇");
        }
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("番外篇不存在: " + sideStoryId));
        return sideStoryWorkflowService.buildOutlineVariables(project, sideStory);
    }

    private Map<String, String> buildSideStoryChapterOutlineVariables(ProjectEntity project, PromptExploreContext ctx) {
        Long sideStoryId = ctx.getSideStoryId();
        Integer chapterNumber = ctx.getSideStoryChapterNumber();
        if (sideStoryId == null) {
            throw new IllegalArgumentException("请选择番外篇");
        }
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("番外篇不存在: " + sideStoryId));
        return sideStoryWorkflowService.buildChapterOutlineVariables(project, sideStory,
                chapterNumber != null ? chapterNumber : 1);
    }

    private Map<String, String> buildSideStoryWritingVariables(ProjectEntity project, PromptExploreContext ctx) {
        Long sideStoryId = ctx.getSideStoryId();
        Integer chapterNumber = ctx.getSideStoryChapterNumber();
        if (sideStoryId == null) {
            throw new IllegalArgumentException("请选择番外篇");
        }
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("番外篇不存在: " + sideStoryId));
        return sideStoryWorkflowService.buildWritingVariables(project, sideStory,
                chapterNumber != null ? chapterNumber : 1);
    }

    // ═══════ Helpers ═══════

    private String loadStepGuidance(Long projectId, WorkflowStep step) {
        return stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");
    }

    private String loadRawStepGuidance(Long projectId, WorkflowStep step) {
        return stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(StepGuidanceEntity::getGuidance)
                .orElse("");
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
