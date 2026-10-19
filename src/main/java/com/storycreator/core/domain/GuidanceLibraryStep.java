package com.storycreator.core.domain;

/**
 * 创作指导库的分类。
 *
 * <p>前 6 项与工作流步骤一一对应（{@link WorkflowStep}），第 7 项 {@link #EXPANSION}（情节拓展）不是工作流步骤：
 * 情节拓展是工作流之后对单章做二次扩写的独立功能，没有步骤状态机，也不能被自动推进链扫到。
 * 因此这里单独建一个枚举，<b>不要</b>往 {@link WorkflowStep} 里加值 —— 那个枚举被工作流状态弹层、
 * 自动推进策略（{@code ordinal()} 比较与 {@code next()}）以及前端步骤列表直接消费，加一个非步骤值会连带出错。
 *
 * <p>数据库 {@code guidance_library.step} 存的是枚举名（VARCHAR(50)），既有 6 个值的历史数据无需迁移。
 */
public enum GuidanceLibraryStep {

    WORLD_BUILDING("世界观设定", WorkflowStep.WORLD_BUILDING),
    CHARACTER_DESIGN("角色设计", WorkflowStep.CHARACTER_DESIGN),
    OUTLINE_GENERATION("大纲生成", WorkflowStep.OUTLINE_GENERATION),
    CHAPTER_WRITING("分章节写作", WorkflowStep.CHAPTER_WRITING),
    POLISHING("润色修改", WorkflowStep.POLISHING),
    PROOFREADING("校对精修", WorkflowStep.PROOFREADING),
    /** 情节拓展（扩写）—— 非工作流步骤 */
    EXPANSION("情节拓展", null);

    private final String displayName;
    private final WorkflowStep workflowStep;

    GuidanceLibraryStep(String displayName, WorkflowStep workflowStep) {
        this.displayName = displayName;
        this.workflowStep = workflowStep;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 该分类是否对应一个真实的工作流步骤（EXPANSION 为 false）。 */
    public boolean isWorkflowStep() {
        return workflowStep != null;
    }

    /** 对应的工作流步骤；非工作流分类返回 null。 */
    public WorkflowStep getWorkflowStep() {
        return workflowStep;
    }

    /** 由工作流步骤反查分类；未知步骤返回 null。 */
    public static GuidanceLibraryStep of(WorkflowStep step) {
        if (step == null) {
            return null;
        }
        for (GuidanceLibraryStep candidate : values()) {
            if (candidate.workflowStep == step) {
                return candidate;
            }
        }
        return null;
    }
}
