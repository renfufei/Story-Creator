package com.storycreator.web;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.image.CharacterImageService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.engine.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.storycreator.workflow.engine.TextProcessingUtils.truncate;

@Service
public class PromptExploreService {

    private final PromptTemplateRegistry promptRegistry;
    private final WorkflowContextBuilder contextBuilder;
    private final CharacterGenerationService characterGenerationService;
    private final OutlineGenerationService outlineGenerationService;
    private final ProofreadingService proofreadingService;
    private final TitleGenerationService titleGenerationService;
    private final CharacterStateService characterStateService;
    private final CharacterImageService characterImageService;
    private final ProjectRepository projectRepository;
    private final WritingRulesRepository writingRulesRepository;
    private final StyleFingerprintRepository styleFingerprintRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final StepGuidanceRepository stepGuidanceRepository;

    public PromptExploreService(PromptTemplateRegistry promptRegistry,
                                WorkflowContextBuilder contextBuilder,
                                CharacterGenerationService characterGenerationService,
                                OutlineGenerationService outlineGenerationService,
                                ProofreadingService proofreadingService,
                                TitleGenerationService titleGenerationService,
                                CharacterStateService characterStateService,
                                CharacterImageService characterImageService,
                                ProjectRepository projectRepository,
                                WritingRulesRepository writingRulesRepository,
                                StyleFingerprintRepository styleFingerprintRepository,
                                WorldSettingRepository worldSettingRepository,
                                CharacterRepository characterRepository,
                                ChapterRepository chapterRepository,
                                ChapterOutlineRepository chapterOutlineRepository,
                                StepGuidanceRepository stepGuidanceRepository) {
        this.promptRegistry = promptRegistry;
        this.contextBuilder = contextBuilder;
        this.characterGenerationService = characterGenerationService;
        this.outlineGenerationService = outlineGenerationService;
        this.proofreadingService = proofreadingService;
        this.titleGenerationService = titleGenerationService;
        this.characterStateService = characterStateService;
        this.characterImageService = characterImageService;
        this.projectRepository = projectRepository;
        this.writingRulesRepository = writingRulesRepository;
        this.styleFingerprintRepository = styleFingerprintRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
    }

    public record ExploreResult(String templateContent, String systemPrompt,
                                Map<String, String> variables, String renderedPrompt) {}

    /**
     * Resolve variables and render prompt for a given step/subStep and project context.
     */
    public ExploreResult resolve(WorkflowStep step, PromptSubStep subStep,
                                 Long projectId, Integer chapterNumber,
                                 Long characterId, Integer cardNumber,
                                 Integer totalCards, Integer volumeNumber) {
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
            variables = buildSubStepVariables(subStep, projectId, chapterNumber, characterId, cardNumber, totalCards, volumeNumber);
            templateContent = promptRegistry.getSubStepTemplate(step, subStep, project.getGenre());
            systemPrompt = promptRegistry.getSubStepSystemPrompt(step, subStep, project.getGenre());
        } else {
            // Main step templates (WORLD_BUILDING, CHAPTER_WRITING, POLISHING)
            int chNum = chapterNumber != null ? chapterNumber : 0;
            WorkflowContext ctx = contextBuilder.build(projectId, chNum);
            variables = ctx.toTemplateVariables();
            templateContent = promptRegistry.getTemplate(step, project.getGenre());
            systemPrompt = promptRegistry.getSystemPrompt(step, project.getGenre());

            // Fix: override stepGuidance with the explored template's step, not project.currentStep
            String correctGuidance = loadStepGuidance(projectId, step);
            variables.put("stepGuidance", correctGuidance);
        }

        String renderedPrompt = promptRegistry.resolveTemplate(templateContent, variables);
        return new ExploreResult(templateContent, systemPrompt, variables, renderedPrompt);
    }

    private Map<String, String> buildSubStepVariables(PromptSubStep subStep, Long projectId,
                                                       Integer chapterNumber, Long characterId,
                                                       Integer cardNumber, Integer totalCards,
                                                       Integer volumeNumber) {
        return switch (subStep) {
            case CHARACTER_CARD -> characterGenerationService.buildCharacterCardVariables(
                    projectId, cardNumber != null ? cardNumber : 1, totalCards != null ? totalCards : 5);
            case CHARACTER_OVERVIEW -> characterGenerationService.buildCharacterOverviewVariables(projectId);
            case CHARACTER_REFINE -> characterGenerationService.buildCharacterRefineVariables(projectId, characterId);
            case VOLUME_ARC -> outlineGenerationService.buildVolumeArcVariables(
                    projectId, volumeNumber != null ? volumeNumber : 1);
            case CHAPTER_OUTLINE -> outlineGenerationService.buildChapterOutlineVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case CHAPTER_OUTLINE_REFINE -> outlineGenerationService.buildChapterOutlineRefineVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case STORY_SUMMARY -> outlineGenerationService.buildStorySummaryVariables(projectId);
            case PROOFREAD_PLOT_SUMMARY -> proofreadingService.buildProofreadPlotSummaryVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_CHARACTER_CHECK -> proofreadingService.buildProofreadCharacterCheckVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_CONSISTENCY -> proofreadingService.buildProofreadConsistencyVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_CONTINUITY -> proofreadingService.buildProofreadContinuityVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_FORESHADOWING -> proofreadingService.buildProofreadForeshadowingVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case PROOFREAD_FIX -> proofreadingService.buildProofreadFixVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case CHAPTER_TITLE -> titleGenerationService.buildChapterTitleVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case CHARACTER_STATES -> characterStateService.buildCharacterStateVariables(
                    projectId, chapterNumber != null ? chapterNumber : 1);
            case IMAGE_PROMPT_AVATAR -> characterImageService.buildImagePromptVariables(
                    projectId, characterId, ImageType.AVATAR);
            case IMAGE_PROMPT_PORTRAIT -> characterImageService.buildImagePromptVariables(
                    projectId, characterId, ImageType.PORTRAIT);
            case WRITING_RULES -> buildWritingRulesVariables(projectId);
            case STYLE_FINGERPRINT -> buildStyleFingerprintVariables(projectId);
            case CHARACTER_BEHAVIOR_BOUNDARIES -> buildCharacterBehaviorBoundariesVariables(projectId, characterId);
            case CHAPTER_EVENT_PLAN -> buildChapterEventPlanVariables(projectId, chapterNumber);
            case CHAPTER_CONTEXT_BRIEFING -> buildChapterContextBriefingVariables(projectId, chapterNumber);
            case CHAPTER_PLOT_REASONING -> buildChapterPlotReasoningVariables(projectId, chapterNumber);
            case CHAPTER_INSTANT_REVIEW -> buildChapterInstantReviewVariables(projectId, chapterNumber);
            case CHAPTER_CONTENT_OPTIMIZATION -> buildChapterContentOptimizationVariables(projectId, chapterNumber);
            case CHAPTER_STORYLINE_UPDATE -> buildChapterStorylineUpdateVariables(projectId, chapterNumber);
            case CHAPTER_DEEP_REVIEW -> buildChapterDeepReviewVariables(projectId, chapterNumber);
            case WORLD_BUILDING_PRIMARY, CHARACTER_DESIGN_PRIMARY, OUTLINE_GENERATION_PRIMARY,
                 CHAPTER_WRITING_PRIMARY, POLISHING_PRIMARY ->
                    throw new IllegalStateException("PRIMARY sub-steps should be intercepted before reaching switch");
        };
    }

    // ═══════ Enhanced sub-step variable builders ═══════

    private Map<String, String> buildWritingRulesVariables(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        String worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent).orElse("");
        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(project.getTitle()));
        vars.put("genre", project.getGenre() != null ? project.getGenre().getDisplayName() : "");
        vars.put("description", safe(project.getDescription()));
        vars.put("worldSetting", worldSetting);
        vars.put("stepGuidance", loadStepGuidance(projectId, WorkflowStep.WORLD_BUILDING));
        return vars;
    }

    private Map<String, String> buildStyleFingerprintVariables(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        String worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent).orElse("");
        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(project.getTitle()));
        vars.put("genre", project.getGenre() != null ? project.getGenre().getDisplayName() : "");
        vars.put("description", safe(project.getDescription()));
        vars.put("worldSetting", worldSetting);
        vars.put("stepGuidance", loadStepGuidance(projectId, WorkflowStep.WORLD_BUILDING));
        return vars;
    }

    private Map<String, String> buildCharacterBehaviorBoundariesVariables(Long projectId, Long characterId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        String worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent).orElse("");

        String characterName = "";
        String cardContent = "";
        if (characterId != null) {
            var charOpt = characterRepository.findById(characterId);
            if (charOpt.isPresent()) {
                CharacterEntity ch = charOpt.get();
                characterName = safe(ch.getName());
                cardContent = safe(ch.getContent());
            }
        } else {
            // Fallback: use first character card
            List<CharacterEntity> chars = characterRepository
                    .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
            if (!chars.isEmpty()) {
                characterName = safe(chars.get(0).getName());
                cardContent = safe(chars.get(0).getContent());
            }
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(project.getTitle()));
        vars.put("genre", project.getGenre() != null ? project.getGenre().getDisplayName() : "");
        vars.put("characterName", characterName);
        vars.put("cardContent", cardContent);
        vars.put("worldSetting", worldSetting);
        vars.put("stepGuidance", loadStepGuidance(projectId, WorkflowStep.CHARACTER_DESIGN));
        return vars;
    }

    private Map<String, String> buildChapterEventPlanVariables(Long projectId, Integer chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int chNum = chapterNumber != null ? chapterNumber : 1;

        String worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent).orElse("");
        String writingRules = writingRulesRepository.findByProjectId(projectId)
                .map(WritingRulesEntity::getContent).orElse("");
        String styleFingerprint = styleFingerprintRepository.findByProjectId(projectId)
                .map(StyleFingerprintEntity::getContent).orElse("");
        String chapterSummary = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chNum)
                .map(ChapterOutlineEntity::getSummary).orElse("");

        // Build characters string
        StringBuilder charSb = new StringBuilder();
        List<CharacterEntity> chars = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        for (CharacterEntity c : chars) {
            if (c.getContent() != null) {
                charSb.append(c.getName()).append(": ").append(truncate(c.getContent(), 200)).append("\n");
            }
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(project.getTitle()));
        vars.put("genre", project.getGenre() != null ? project.getGenre().getDisplayName() : "");
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("chapterSummary", chapterSummary);
        vars.put("worldSetting", worldSetting);
        vars.put("characters", charSb.toString());
        vars.put("writingRules", writingRules);
        vars.put("styleFingerprint", styleFingerprint);
        vars.put("stepGuidance", loadStepGuidance(projectId, WorkflowStep.OUTLINE_GENERATION));
        return vars;
    }

    private Map<String, String> buildChapterContextBriefingVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        // Use contextBuilder to get standard chapter context (characterCards, previousChapterContent, etc.)
        WorkflowContext wfCtx = contextBuilder.build(projectId, chNum);

        String writingRules = writingRulesRepository.findByProjectId(projectId)
                .map(WritingRulesEntity::getContent).orElse("");
        String chapterSummary = wfCtx.getChapterSummary() != null ? wfCtx.getChapterSummary() : "";
        String chapterCards = wfCtx.getCharacterCards() != null ? wfCtx.getCharacterCards() : "";
        String prevContent = wfCtx.getPreviousChapterContent() != null ? wfCtx.getPreviousChapterContent() : "";

        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(wfCtx.getTitle()));
        vars.put("genre", wfCtx.getGenre() != null ? wfCtx.getGenre().getDisplayName() : "");
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("previousChapterContent", prevContent);
        vars.put("chapterSummary", chapterSummary);
        vars.put("writingRules", writingRules);
        vars.put("characterCards", chapterCards);
        vars.put("stepGuidance", loadStepGuidance(projectId, WorkflowStep.CHAPTER_WRITING));
        return vars;
    }

    private Map<String, String> buildChapterPlotReasoningVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        WorkflowContext wfCtx = contextBuilder.build(projectId, chNum);

        String eventPlan = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chNum)
                .map(ChapterOutlineEntity::getEventPlan).orElse("");
        String chapterSummary = wfCtx.getChapterSummary() != null ? wfCtx.getChapterSummary() : "";
        String chapterCards = wfCtx.getCharacterCards() != null ? wfCtx.getCharacterCards() : "";

        // Load writingBriefing from chapter entity
        String writingBriefing = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum)
                .map(ChapterEntity::getWritingBriefing).orElse("");
        if (writingBriefing == null) writingBriefing = "";

        Map<String, String> vars = new HashMap<>();
        vars.put("title", safe(wfCtx.getTitle()));
        vars.put("genre", wfCtx.getGenre() != null ? wfCtx.getGenre().getDisplayName() : "");
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("chapterSummary", chapterSummary);
        vars.put("eventPlan", eventPlan);
        vars.put("writingBriefing", writingBriefing);
        vars.put("characterCards", chapterCards);
        vars.put("stepGuidance", loadStepGuidance(projectId, WorkflowStep.CHAPTER_WRITING));
        return vars;
    }

    private Map<String, String> buildChapterInstantReviewVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).orElse(null);
        String writingReasoning = "";
        String contentDraft = "";
        if (chapter != null) {
            writingReasoning = safe(chapter.getWritingReasoning());
            contentDraft = safe(chapter.getContent());
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("writingReasoning", writingReasoning);
        vars.put("contentDraft", contentDraft);
        return vars;
    }

    private Map<String, String> buildChapterContentOptimizationVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).orElse(null);
        String contentDraft = "";
        String instantReview = "";
        if (chapter != null) {
            contentDraft = safe(chapter.getContent());
            instantReview = safe(chapter.getInstantReview());
        }

        String writingRules = writingRulesRepository.findByProjectId(projectId)
                .map(WritingRulesEntity::getContent).orElse("");
        String styleFingerprint = styleFingerprintRepository.findByProjectId(projectId)
                .map(StyleFingerprintEntity::getContent).orElse("");

        Map<String, String> vars = new HashMap<>();
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("contentDraft", contentDraft);
        vars.put("instantReview", instantReview);
        vars.put("writingRules", writingRules);
        vars.put("styleFingerprint", styleFingerprint);
        return vars;
    }

    private Map<String, String> buildChapterStorylineUpdateVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).orElse(null);
        String optimizedContent = "";
        if (chapter != null) {
            optimizedContent = safe(chapter.getContent());
        }

        // Load previous chapter's storyline snapshot
        String prevSnapshot = "";
        if (chNum > 1) {
            prevSnapshot = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum - 1)
                    .map(ChapterEntity::getStorylineSnapshot).orElse("");
            if (prevSnapshot == null) prevSnapshot = "";
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("optimizedContent", optimizedContent);
        vars.put("previousStorylineSnapshot", prevSnapshot);
        return vars;
    }

    private Map<String, String> buildChapterDeepReviewVariables(Long projectId, Integer chapterNumber) {
        int chNum = chapterNumber != null ? chapterNumber : 1;

        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).orElse(null);
        String finalContent = "";
        if (chapter != null) {
            finalContent = safe(chapter.getContent());
        }

        String writingRules = writingRulesRepository.findByProjectId(projectId)
                .map(WritingRulesEntity::getContent).orElse("");
        String styleFingerprint = styleFingerprintRepository.findByProjectId(projectId)
                .map(StyleFingerprintEntity::getContent).orElse("");

        // Load previous chapter's plot summary
        String prevPlotSummary = "";
        if (chNum > 1) {
            prevPlotSummary = chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum - 1)
                    .map(ChapterEntity::getPlotSummary).orElse("");
            if (prevPlotSummary == null) prevPlotSummary = "";
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("chapterNumber", String.valueOf(chNum));
        vars.put("finalContent", finalContent);
        vars.put("writingRules", writingRules);
        vars.put("styleFingerprint", styleFingerprint);
        vars.put("previousPlotSummary", prevPlotSummary);
        return vars;
    }

    // ═══════ Helpers ═══════

    private String loadStepGuidance(Long projectId, WorkflowStep step) {
        return stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
