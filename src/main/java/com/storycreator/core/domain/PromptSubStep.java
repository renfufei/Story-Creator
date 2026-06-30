package com.storycreator.core.domain;

import java.util.Map;

public enum PromptSubStep {
    // PRIMARY sub-steps (one per main workflow step)
    WORLD_BUILDING_PRIMARY("世界观核心", 10),
    CHARACTER_DESIGN_PRIMARY("角色设计核心", 40),
    OUTLINE_GENERATION_PRIMARY("大纲核心", 110),
    CHAPTER_WRITING_PRIMARY("写作核心", 170),
    POLISHING_PRIMARY("润色核心", 260),

    // ENHANCED: WORLD_BUILDING sub-steps (preparation phase)
    WRITING_RULES("写作规则生成", 20),
    STYLE_FINGERPRINT("风格指纹提取", 30),

    // CHARACTER_DESIGN sub-steps
    CHARACTER_OVERVIEW("角色总览", 50),
    CHARACTER_CARD("角色卡生成", 60),
    CHARACTER_REFINE("角色精修", 70),
    CHARACTER_BEHAVIOR_BOUNDARIES("角色行为边界", 80),
    IMAGE_PROMPT_AVATAR("头像提示词", 90),
    IMAGE_PROMPT_PORTRAIT("立绘提示词", 100),

    // OUTLINE_GENERATION sub-steps
    VOLUME_ARC("卷弧线生成", 120),
    CHAPTER_OUTLINE("章节大纲", 130),
    CHAPTER_OUTLINE_REFINE("章节大纲精修", 140),
    STORY_SUMMARY("故事总纲", 150),
    CHAPTER_EVENT_PLAN("章节事件计划", 160),

    // ENHANCED: CHAPTER_WRITING sub-steps (7-step cycle)
    CHAPTER_CONTEXT_BRIEFING("前文梳理", 180),
    CHAPTER_PLOT_REASONING("剧情推演", 190),
    CHAPTER_INSTANT_REVIEW("即时审查", 200),
    CHAPTER_CONTENT_OPTIMIZATION("内容优化", 210),
    CHAPTER_STORYLINE_UPDATE("故事线更新", 220),
    CHAPTER_DEEP_REVIEW("深度审查", 230),

    // POLISHING auxiliary sub-steps
    CHARACTER_STATES("角色状态", 240),
    CHAPTER_TITLE("章节标题", 250),

    // PROOFREADING sub-steps
    PROOFREAD_PLOT_SUMMARY("情节摘要", 270),
    PROOFREAD_CHARACTER_CHECK("角色名校验", 280),
    PROOFREAD_CONSISTENCY("一致性检查", 290),
    PROOFREAD_CONTINUITY("衔接检查", 300),
    PROOFREAD_FORESHADOWING("伏笔检查", 310),
    PROOFREAD_FIX("校对修复", 320);

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
                || this == CHARACTER_DESIGN_PRIMARY
                || this == OUTLINE_GENERATION_PRIMARY
                || this == CHAPTER_WRITING_PRIMARY
                || this == POLISHING_PRIMARY;
    }

    private static final Map<PromptSubStep, WorkflowStep> PARENT_STEP_MAP = Map.ofEntries(
            Map.entry(WORLD_BUILDING_PRIMARY, WorkflowStep.WORLD_BUILDING),
            Map.entry(CHARACTER_DESIGN_PRIMARY, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(OUTLINE_GENERATION_PRIMARY, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_WRITING_PRIMARY, WorkflowStep.CHAPTER_WRITING),
            Map.entry(POLISHING_PRIMARY, WorkflowStep.POLISHING),
            Map.entry(WRITING_RULES, WorkflowStep.WORLD_BUILDING),
            Map.entry(STYLE_FINGERPRINT, WorkflowStep.WORLD_BUILDING),
            Map.entry(CHARACTER_CARD, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_OVERVIEW, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_REFINE, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_BEHAVIOR_BOUNDARIES, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(IMAGE_PROMPT_AVATAR, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(IMAGE_PROMPT_PORTRAIT, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(VOLUME_ARC, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_OUTLINE, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_OUTLINE_REFINE, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(STORY_SUMMARY, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_EVENT_PLAN, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_CONTEXT_BRIEFING, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHAPTER_PLOT_REASONING, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHAPTER_INSTANT_REVIEW, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHAPTER_CONTENT_OPTIMIZATION, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHAPTER_STORYLINE_UPDATE, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHAPTER_DEEP_REVIEW, WorkflowStep.CHAPTER_WRITING),
            Map.entry(CHARACTER_STATES, WorkflowStep.POLISHING),
            Map.entry(CHAPTER_TITLE, WorkflowStep.POLISHING),
            Map.entry(PROOFREAD_PLOT_SUMMARY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_CHARACTER_CHECK, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_CONSISTENCY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_CONTINUITY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_FORESHADOWING, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_FIX, WorkflowStep.PROOFREADING)
    );

    public WorkflowStep getParentStep() {
        return PARENT_STEP_MAP.get(this);
    }
}
