package com.storycreator.core.domain;

import java.util.Map;

public enum PromptSubStep {
    // PRIMARY sub-steps (one per main workflow step)
    WORLD_BUILDING_PRIMARY("世界观核心", 10),
    CHAPTER_WRITING_PRIMARY("写作核心", 170),
    POLISHING_PRIMARY("润色核心", 260),

    // CHARACTER_DESIGN sub-steps
    CHARACTER_OVERVIEW("角色总览", 65),
    CHARACTER_CARD("角色卡生成", 60),
    CHARACTER_REFINE("角色精修", 70),
    IMAGE_PROMPT_AVATAR("头像提示词", 90),
    IMAGE_PROMPT_PORTRAIT("立绘提示词", 100),

    // OUTLINE_GENERATION sub-steps
    VOLUME_ARC("卷弧线生成", 120),
    VOLUME_CHARACTERS("分卷角色生成", 125),
    CHAPTER_OUTLINE("章节大纲", 130),
    CHAPTER_OUTLINE_REFINE("章节大纲精修", 140),
    STORY_SUMMARY("故事总纲", 150),

    // CHAPTER_WRITING sub-steps
    CHAPTER_CONTEXT_BRIEFING("前文梳理", 180),

    // POLISHING auxiliary sub-steps
    CHARACTER_STATES("角色状态", 240),

    // PROOFREADING sub-steps
    PROOFREAD_PLOT_SUMMARY("情节摘要", 270),
    PROOFREAD_FORESHADOWING("伏笔检查", 310),
    PROOFREAD_FIX("校对修复", 320),

    // SIDE_STORY sub-steps
    SIDE_STORY_OUTLINE("番外故事线", 330),
    SIDE_STORY_CHAPTER_OUTLINE("番外章节大纲", 340),
    SIDE_STORY_WRITING("番外写作", 350),

    // EXPANSION sub-step
    CHAPTER_EXPANSION("章节扩写", 360),

    // TXT IMPORT reverse engineering sub-steps
    REVERSE_WORLD_BUILDING("逆向世界观", 370),
    REVERSE_CHARACTER_EXTRACTION("逆向角色提取", 380),
    REVERSE_OUTLINE_GENERATION("逆向大纲生成", 390);

    private final String displayName;
    private final int sortOrder;

    PromptSubStep(String displayName, int sortOrder) {
        this.displayName = displayName;
        this.sortOrder = sortOrder;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isPrimary() {
        return this == WORLD_BUILDING_PRIMARY
                || this == CHAPTER_WRITING_PRIMARY
                || this == POLISHING_PRIMARY;
    }

    private static final Map<PromptSubStep, WorkflowStep> PARENT_STEP_MAP = Map.ofEntries(
            Map.entry(WORLD_BUILDING_PRIMARY, WorkflowStep.WORLD_BUILDING),
            Map.entry(CHAPTER_WRITING_PRIMARY, WorkflowStep.CHAPTER_WRITING),
            Map.entry(POLISHING_PRIMARY, WorkflowStep.POLISHING),
            Map.entry(CHARACTER_CARD, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_OVERVIEW, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_REFINE, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(IMAGE_PROMPT_AVATAR, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(IMAGE_PROMPT_PORTRAIT, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(VOLUME_ARC, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(VOLUME_CHARACTERS, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_OUTLINE, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_OUTLINE_REFINE, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(STORY_SUMMARY, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_CONTEXT_BRIEFING, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHARACTER_STATES, WorkflowStep.POLISHING),
            Map.entry(PROOFREAD_PLOT_SUMMARY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_FORESHADOWING, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_FIX, WorkflowStep.PROOFREADING),
            Map.entry(SIDE_STORY_OUTLINE, WorkflowStep.CHAPTER_WRITING),
            Map.entry(SIDE_STORY_CHAPTER_OUTLINE, WorkflowStep.CHAPTER_WRITING),
            Map.entry(SIDE_STORY_WRITING, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHAPTER_EXPANSION, WorkflowStep.CHAPTER_WRITING),
            Map.entry(REVERSE_WORLD_BUILDING, WorkflowStep.WORLD_BUILDING),
            Map.entry(REVERSE_CHARACTER_EXTRACTION, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(REVERSE_OUTLINE_GENERATION, WorkflowStep.OUTLINE_GENERATION)
    );

    public WorkflowStep getParentStep() {
        return PARENT_STEP_MAP.get(this);
    }
}
