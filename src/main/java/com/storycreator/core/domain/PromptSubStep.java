package com.storycreator.core.domain;

import java.util.Map;

public enum PromptSubStep {
    // CHARACTER_DESIGN sub-steps
    CHARACTER_CARD("角色卡生成"),
    CHARACTER_OVERVIEW("角色总览"),
    CHARACTER_REFINE("角色精修"),

    // OUTLINE_GENERATION sub-steps
    VOLUME_ARC("卷弧线生成"),
    CHAPTER_OUTLINE("章节大纲"),
    CHAPTER_OUTLINE_REFINE("章节大纲精修"),
    STORY_SUMMARY("故事总纲"),

    // PROOFREADING sub-steps
    PROOFREAD_PLOT_SUMMARY("情节摘要"),
    PROOFREAD_CHARACTER_CHECK("角色名校验"),
    PROOFREAD_CONSISTENCY("一致性检查"),
    PROOFREAD_CONTINUITY("衔接检查"),
    PROOFREAD_FORESHADOWING("伏笔检查"),
    PROOFREAD_FIX("校对修复"),

    // POLISHING auxiliary sub-steps
    CHAPTER_TITLE("章节标题"),
    CHARACTER_STATES("角色状态");

    private final String displayName;

    PromptSubStep(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    private static final Map<PromptSubStep, WorkflowStep> PARENT_STEP_MAP = Map.ofEntries(
            Map.entry(CHARACTER_CARD, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_OVERVIEW, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(CHARACTER_REFINE, WorkflowStep.CHARACTER_DESIGN),
            Map.entry(VOLUME_ARC, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_OUTLINE, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(CHAPTER_OUTLINE_REFINE, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(STORY_SUMMARY, WorkflowStep.OUTLINE_GENERATION),
            Map.entry(PROOFREAD_PLOT_SUMMARY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_CHARACTER_CHECK, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_CONSISTENCY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_CONTINUITY, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_FORESHADOWING, WorkflowStep.PROOFREADING),
            Map.entry(PROOFREAD_FIX, WorkflowStep.PROOFREADING),
            Map.entry(CHAPTER_TITLE, WorkflowStep.POLISHING),
            Map.entry(CHARACTER_STATES, WorkflowStep.POLISHING)
    );

    public WorkflowStep getParentStep() {
        return PARENT_STEP_MAP.get(this);
    }
}
