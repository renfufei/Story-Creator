package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PromptTemplateRegistry {

    private final PromptTemplateRepository repository;
    private final BuiltinTemplateLoader builtinLoader;

    private static final int LONG_VALUE_THRESHOLD = 50;
    private static final Set<String> SHORT_VARIABLES = Set.of(
            "title", "genre", "chapterNumber", "totalChapters", "cardNumber", "totalCards",
            "volumeNumber", "totalVolumes", "chapterStart", "chapterEnd", "dimList", "characterNames",
            "charNames", "phaseHint", "gender", "age", "role",
            "stepGuidance", "referenceMaterials",
            "sideStoryTitle", "chapterWordCount",
            "recurringCount", "tempCount"
    );

    public static final Map<PromptSubStep, List<String>> SUB_STEP_VARIABLES = Map.ofEntries(
            Map.entry(PromptSubStep.WORLD_BUILDING_PRIMARY, List.of("title", "genre", "description", "stepGuidance", "referenceMaterials")),
            Map.entry(PromptSubStep.CHAPTER_WRITING_PRIMARY, List.of("title", "genre", "worldSetting", "characters", "characterCards", "overallOutline", "chapterNumber", "chapterTitle", "chapterSummary", "previousContext", "previousCharacterStates", "nextChapterTitle", "nextChapterSummary", "stepGuidance", "chapterWordCount", "chapterWordCountMin", "chapterWordCountMax", "referenceMaterials")),
            Map.entry(PromptSubStep.POLISHING_PRIMARY, List.of("title", "genre", "content", "polishNote", "stepGuidance", "referenceMaterials")),
            Map.entry(PromptSubStep.CHARACTER_CARD, List.of("title", "genre", "description", "worldSetting", "previousContext", "cardNumber", "totalCards", "stepGuidance")),
            Map.entry(PromptSubStep.CHARACTER_OVERVIEW, List.of("title", "genre", "description", "previousSummaries", "stepGuidance")),
            Map.entry(PromptSubStep.CHARACTER_REFINE, List.of("title", "genre", "description", "worldSetting", "allSummaries", "cardContent", "stepGuidance")),
            Map.entry(PromptSubStep.VOLUME_ARC, List.of("title", "genre", "description", "worldSetting", "characters", "allCharacters", "storySummary", "totalChapters", "volumeNumber", "chapterStart", "chapterEnd", "previousArcs", "stepGuidance")),
            Map.entry(PromptSubStep.VOLUME_CHARACTERS, List.of("title", "genre", "worldSetting", "volumeArc", "existingCharacters", "volumeNumber", "totalVolumes", "recurringCount", "tempCount", "stepGuidance")),
            Map.entry(PromptSubStep.CHAPTER_OUTLINE, List.of("title", "genre", "characters", "allCharacters", "storySummary", "chapterNumber", "totalChapters", "chapterStart", "chapterEnd", "phaseHint", "contextInfo", "stepGuidance")),
            Map.entry(PromptSubStep.CHAPTER_OUTLINE_REFINE, List.of("title", "genre", "chapterNumber", "totalChapters", "contextInfo", "currentOutline", "allCharacters")),
            Map.entry(PromptSubStep.STORY_SUMMARY, List.of("title", "genre", "description", "totalChapters", "arcsInfo", "stepGuidance")),
            Map.entry(PromptSubStep.PROOFREAD_PLOT_SUMMARY, List.of("chapterContent")),
            Map.entry(PromptSubStep.PROOFREAD_FORESHADOWING, List.of("accumulatedForeshadowing", "chapterNumber", "chapterContent")),
            Map.entry(PromptSubStep.PROOFREAD_FIX, List.of("reportSummary", "originalContent")),
            Map.entry(PromptSubStep.CHARACTER_STATES, List.of("dimList", "charNames", "prevStates", "chapterExcerpt")),
            Map.entry(PromptSubStep.IMAGE_PROMPT_AVATAR, List.of("gender", "age", "appearance", "personality", "role")),
            Map.entry(PromptSubStep.IMAGE_PROMPT_PORTRAIT, List.of("gender", "age", "appearance", "personality", "role", "background", "motivation")),
            Map.entry(PromptSubStep.CHAPTER_CONTEXT_BRIEFING, List.of("title", "genre", "chapterNumber", "previousChapterContent", "chapterSummary", "writingRules", "characterCards", "stepGuidance")),
            Map.entry(PromptSubStep.SIDE_STORY_OUTLINE, List.of("worldSetting", "allCharacters", "storySummary", "sideStoryTitle", "sideStoryDescription", "creativeGuidance", "attachedVolumeContext")),
            Map.entry(PromptSubStep.SIDE_STORY_CHAPTER_OUTLINE, List.of("sideStoryTitle", "sideStoryOutline", "allCharacters", "chapterNumber", "totalChapters", "previousOutlines")),
            Map.entry(PromptSubStep.SIDE_STORY_WRITING, List.of("characterCards", "sideStoryTitle", "sideStoryOutline", "chapterNumber", "chapterTitle", "chapterSummary", "previousContext", "chapterWordCount")),
            Map.entry(PromptSubStep.CHAPTER_EXPANSION, List.of("title", "genre", "chapterNumber", "chapterTitle", "originalContent", "expansionGuidance", "chapterWordCount")),
            Map.entry(PromptSubStep.REVERSE_WORLD_BUILDING, List.of("title", "genre", "sampledChapters", "chapterCount")),
            Map.entry(PromptSubStep.REVERSE_CHARACTER_EXTRACTION, List.of("title", "genre", "sampledChapters", "chapterCount", "worldSetting")),
            Map.entry(PromptSubStep.REVERSE_OUTLINE_GENERATION, List.of("title", "genre", "sampledChapters", "chapterCount", "worldSetting", "characters"))
    );

    public PromptTemplateRegistry(PromptTemplateRepository repository, BuiltinTemplateLoader builtinLoader) {
        this.repository = repository;
        this.builtinLoader = builtinLoader;
    }

    public String getTemplate(WorkflowStep step, Genre genre) {
        // DB custom template first
        return repository.findByStepAndGenreAndIsDefaultTrue(step, genre)
                .or(() -> repository.findByStepAndGenreIsNullAndIsDefaultTrue(step))
                .map(PromptTemplateEntity::getTemplate)
                // Fallback to builtin
                .or(() -> builtinLoader.findMainStep(step, genre).map(BuiltinTemplate::template))
                .orElseThrow(() -> new IllegalStateException("No template found for step: " + step));
    }

    public String getSystemPrompt(WorkflowStep step, Genre genre) {
        return repository.findByStepAndGenreAndIsDefaultTrue(step, genre)
                .or(() -> repository.findByStepAndGenreIsNullAndIsDefaultTrue(step))
                .map(PromptTemplateEntity::getSystemPrompt)
                // Fallback to builtin
                .or(() -> builtinLoader.findMainStep(step, genre).map(BuiltinTemplate::systemPrompt))
                .orElse(null);
    }

    public String getSubStepTemplate(WorkflowStep step, PromptSubStep subStep, Genre genre) {
        if (subStep == null) throw new IllegalArgumentException("subStep must not be null");
        return repository.findByStepAndSubStepAndGenreAndIsDefaultTrue(step, subStep, genre)
                .or(() -> repository.findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(step, subStep))
                .map(PromptTemplateEntity::getTemplate)
                // Fallback to builtin
                .or(() -> builtinLoader.findSubStep(step, subStep, genre).map(BuiltinTemplate::template))
                .orElseThrow(() -> new IllegalStateException("No template found for sub-step: " + step + "/" + subStep));
    }

    public String getSubStepSystemPrompt(WorkflowStep step, PromptSubStep subStep, Genre genre) {
        if (subStep == null) return null;
        return repository.findByStepAndSubStepAndGenreAndIsDefaultTrue(step, subStep, genre)
                .or(() -> repository.findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(step, subStep))
                .map(PromptTemplateEntity::getSystemPrompt)
                // Fallback to builtin
                .or(() -> builtinLoader.findSubStep(step, subStep, genre).map(BuiltinTemplate::systemPrompt))
                .orElse(null);
    }

    public String resolveTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            if (!SHORT_VARIABLES.contains(entry.getKey()) && value.length() > LONG_VALUE_THRESHOLD) {
                // Replace backticks with fullwidth backtick to avoid breaking the wrapper
                value = value.replace("`", "\uff40");
                value = "\n```\n" + value + "\n```";
            }
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result;
    }
}
