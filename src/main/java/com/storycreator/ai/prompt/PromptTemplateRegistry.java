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

    private static final Set<String> SHORT_VARIABLES = Set.of(
            "title", "genre", "description", "chapterNumber", "totalChapters",
            "chapterTitle", "chapterWordCount", "chapterWordCountMin",
            "chapterWordCountMax", "nextChapterTitle",
            "cardNumber", "totalCards", "volumeNumber", "chapterStart", "chapterEnd",
            "phaseHint", "dimList"
    );

    public static final Map<PromptSubStep, List<String>> SUB_STEP_VARIABLES = Map.ofEntries(
            Map.entry(PromptSubStep.CHARACTER_CARD, List.of("title", "genre", "description", "worldSetting", "previousContext", "cardNumber", "totalCards", "stepGuidance")),
            Map.entry(PromptSubStep.CHARACTER_OVERVIEW, List.of("title", "genre", "description", "previousSummaries", "stepGuidance")),
            Map.entry(PromptSubStep.CHARACTER_REFINE, List.of("title", "genre", "description", "worldSetting", "allSummaries", "cardContent", "stepGuidance")),
            Map.entry(PromptSubStep.VOLUME_ARC, List.of("title", "genre", "description", "worldSetting", "characters", "totalChapters", "volumeNumber", "chapterStart", "chapterEnd", "previousArcs", "stepGuidance")),
            Map.entry(PromptSubStep.CHAPTER_OUTLINE, List.of("title", "genre", "worldSetting", "characters", "chapterNumber", "totalChapters", "chapterStart", "chapterEnd", "phaseHint", "contextInfo", "stepGuidance")),
            Map.entry(PromptSubStep.CHAPTER_OUTLINE_REFINE, List.of("title", "genre", "worldSetting", "characters", "chapterNumber", "totalChapters", "contextInfo", "currentOutline", "stepGuidance")),
            Map.entry(PromptSubStep.STORY_SUMMARY, List.of("title", "genre", "description", "totalChapters", "arcsInfo", "stepGuidance")),
            Map.entry(PromptSubStep.PROOFREAD_PLOT_SUMMARY, List.of("chapterContent")),
            Map.entry(PromptSubStep.PROOFREAD_CHARACTER_CHECK, List.of("characterNames", "chapterContent")),
            Map.entry(PromptSubStep.PROOFREAD_CONSISTENCY, List.of("characterSummaries", "previousPlotSummary", "chapterContent")),
            Map.entry(PromptSubStep.PROOFREAD_CONTINUITY, List.of("previousEnd", "currentStart")),
            Map.entry(PromptSubStep.PROOFREAD_FORESHADOWING, List.of("accumulatedForeshadowing", "chapterNumber", "chapterContent")),
            Map.entry(PromptSubStep.PROOFREAD_FIX, List.of("reportSummary", "originalContent")),
            Map.entry(PromptSubStep.CHAPTER_TITLE, List.of("contentPreview")),
            Map.entry(PromptSubStep.CHARACTER_STATES, List.of("dimList", "charNames", "prevStates", "chapterExcerpt"))
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
            if (!SHORT_VARIABLES.contains(entry.getKey()) && !value.isEmpty() && value.length() > 50) {
                value = wrapWithDelimiters(value);
            }
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result;
    }

    /**
     * Sanitize backticks in content and wrap with triple-backtick delimiters.
     */
    private String wrapWithDelimiters(String content) {
        String sanitized = content.replace('`', '\uff40'); // replace ` with fullwidth ｀
        return "\n```\n" + sanitized + "\n```\n";
    }
}
