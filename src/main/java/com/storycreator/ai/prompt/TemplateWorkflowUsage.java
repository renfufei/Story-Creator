package com.storycreator.ai.prompt;

import com.storycreator.core.domain.PromptSubStep;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.storycreator.ai.prompt.TemplateWorkflowTag.*;

/**
 * Declares which workflow(s) each PromptSubStep is used by.
 * STANDARD = used by the DEFAULT strategy (also used by ENHANCED since it's a superset).
 * ENHANCED = used only by the ENHANCED strategy.
 * IMAGE = used only by image generation features.
 */
public final class TemplateWorkflowUsage {

    private static final Map<PromptSubStep, Set<TemplateWorkflowTag>> USAGE_MAP;

    static {
        var map = new EnumMap<PromptSubStep, Set<TemplateWorkflowTag>>(PromptSubStep.class);

        // Primary steps — used by both standard and enhanced
        map.put(PromptSubStep.WORLD_BUILDING_PRIMARY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHARACTER_DESIGN_PRIMARY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.OUTLINE_GENERATION_PRIMARY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHAPTER_WRITING_PRIMARY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.POLISHING_PRIMARY, Set.of(STANDARD, ENHANCED));

        // Standard sub-steps (used by both)
        map.put(PromptSubStep.CHARACTER_OVERVIEW, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHARACTER_CARD, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHARACTER_REFINE, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.VOLUME_ARC, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHAPTER_OUTLINE, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHAPTER_OUTLINE_REFINE, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.STORY_SUMMARY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHARACTER_STATES, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.CHAPTER_TITLE, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.PROOFREAD_PLOT_SUMMARY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.PROOFREAD_CHARACTER_CHECK, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.PROOFREAD_CONSISTENCY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.PROOFREAD_CONTINUITY, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.PROOFREAD_FORESHADOWING, Set.of(STANDARD, ENHANCED));
        map.put(PromptSubStep.PROOFREAD_FIX, Set.of(STANDARD, ENHANCED));

        // Enhanced-only sub-steps
        map.put(PromptSubStep.WRITING_RULES, Set.of(ENHANCED));
        map.put(PromptSubStep.STYLE_FINGERPRINT, Set.of(ENHANCED));
        map.put(PromptSubStep.CHARACTER_BEHAVIOR_BOUNDARIES, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_EVENT_PLAN, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_CONTEXT_BRIEFING, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_PLOT_REASONING, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_INSTANT_REVIEW, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_CONTENT_OPTIMIZATION, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_STORYLINE_UPDATE, Set.of(ENHANCED));
        map.put(PromptSubStep.CHAPTER_DEEP_REVIEW, Set.of(ENHANCED));

        // Image-only sub-steps
        map.put(PromptSubStep.IMAGE_PROMPT_AVATAR, Set.of(IMAGE));
        map.put(PromptSubStep.IMAGE_PROMPT_PORTRAIT, Set.of(IMAGE));

        USAGE_MAP = Map.copyOf(map);
    }

    private TemplateWorkflowUsage() {}

    public static Set<TemplateWorkflowTag> getTagsFor(PromptSubStep subStep) {
        return USAGE_MAP.getOrDefault(subStep, Set.of());
    }
}
