package com.storycreator.ai.prompt;

import com.storycreator.core.domain.PromptSubStep;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.storycreator.ai.prompt.TemplateWorkflowTag.*;

/**
 * Declares which workflow(s) each PromptSubStep is used by.
 * STANDARD = used by the default auto-run strategy.
 * IMAGE = used only by image generation features.
 * SIDE_STORY = used only by side-story features.
 * REVERSE = used only by the TXT reverse-engineering flow.
 */
public final class TemplateWorkflowUsage {

    private static final Map<PromptSubStep, Set<TemplateWorkflowTag>> USAGE_MAP;

    static {
        var map = new EnumMap<PromptSubStep, Set<TemplateWorkflowTag>>(PromptSubStep.class);

        // Primary steps
        map.put(PromptSubStep.WORLD_BUILDING_PRIMARY, Set.of(STANDARD));
        map.put(PromptSubStep.CHAPTER_WRITING_PRIMARY, Set.of(STANDARD));
        map.put(PromptSubStep.POLISHING_PRIMARY, Set.of(STANDARD));

        // Standard sub-steps
        map.put(PromptSubStep.CHARACTER_OVERVIEW, Set.of(STANDARD));
        map.put(PromptSubStep.CHARACTER_CARD, Set.of(STANDARD));
        map.put(PromptSubStep.CHARACTER_REFINE, Set.of(STANDARD));
        map.put(PromptSubStep.VOLUME_ARC, Set.of(STANDARD));
        map.put(PromptSubStep.VOLUME_CHARACTERS, Set.of(STANDARD));
        map.put(PromptSubStep.CHAPTER_OUTLINE, Set.of(STANDARD));
        map.put(PromptSubStep.CHAPTER_OUTLINE_REFINE, Set.of(STANDARD));
        map.put(PromptSubStep.STORY_SUMMARY, Set.of(STANDARD));
        map.put(PromptSubStep.CHAPTER_CONTEXT_BRIEFING, Set.of(STANDARD));
        map.put(PromptSubStep.CHARACTER_STATES, Set.of(STANDARD));
        map.put(PromptSubStep.PROOFREAD_PLOT_SUMMARY, Set.of(STANDARD));
        map.put(PromptSubStep.PROOFREAD_FORESHADOWING, Set.of(STANDARD));
        map.put(PromptSubStep.PROOFREAD_FIX, Set.of(STANDARD));

        // Image-only sub-steps
        map.put(PromptSubStep.IMAGE_PROMPT_AVATAR, Set.of(IMAGE));
        map.put(PromptSubStep.IMAGE_PROMPT_PORTRAIT, Set.of(IMAGE));

        // Side story sub-steps
        map.put(PromptSubStep.SIDE_STORY_OUTLINE, Set.of(SIDE_STORY));
        map.put(PromptSubStep.SIDE_STORY_CHAPTER_OUTLINE, Set.of(SIDE_STORY));
        map.put(PromptSubStep.SIDE_STORY_WRITING, Set.of(SIDE_STORY));

        // TXT 导入逆向工程子步骤（这些模板只被逆向流程使用，不打 STANDARD）
        map.put(PromptSubStep.REVERSE_WORLD_BUILDING, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_CHARACTER_EXTRACTION, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_OUTLINE_GENERATION, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_CHAPTER_OUTLINE, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_STORY_ARC, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_FINAL_STORY_OUTLINE, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_FINAL_WORLD, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_FINAL_CHARACTERS, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_CHARACTER_LIST, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_CHARACTER_CARD, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_GENRE, Set.of(REVERSE));
        map.put(PromptSubStep.REVERSE_SYNOPSIS, Set.of(REVERSE));

        USAGE_MAP = Map.copyOf(map);
    }

    private TemplateWorkflowUsage() {}

    public static Set<TemplateWorkflowTag> getTagsFor(PromptSubStep subStep) {
        return USAGE_MAP.getOrDefault(subStep, Set.of());
    }
}
