package com.storycreator.core.domain;

import java.util.Arrays;
import java.util.List;

public enum WorkflowStep {
    WORLD_BUILDING("世界观设定", 1),
    CHARACTER_DESIGN("角色设计", 2),
    OUTLINE_GENERATION("大纲生成", 3),
    CHAPTER_WRITING("分章节写作", 4),
    POLISHING("润色修改", 5),
    PROOFREADING("校对精修", 6);

    private final String displayName;
    private final int order;

    WorkflowStep(String displayName, int order) {
        this.displayName = displayName;
        this.order = order;
    }

    /**
     * Returns the statuses that can be manually set for this step in the workflow state modal.
     * Filters by manualSettable flag, and PARTIALLY_DONE is only applicable to multi-chapter steps.
     */
    public List<StepStatus> getAllowedManualStatuses() {
        boolean multiChapter = (this == CHAPTER_WRITING || this == POLISHING || this == PROOFREADING);
        return Arrays.stream(StepStatus.values())
                .filter(StepStatus::isManualSettable)
                .filter(s -> s != StepStatus.PARTIALLY_DONE || multiChapter)
                .toList();
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getOrder() {
        return order;
    }

    public WorkflowStep next() {
        WorkflowStep[] steps = values();
        int nextOrdinal = this.ordinal() + 1;
        if (nextOrdinal >= steps.length) {
            return null;
        }
        return steps[nextOrdinal];
    }

    public WorkflowStep previous() {
        int prevOrdinal = this.ordinal() - 1;
        if (prevOrdinal < 0) {
            return null;
        }
        return values()[prevOrdinal];
    }
}
