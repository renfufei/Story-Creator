package com.storycreator.core.domain;

public enum StepStatus {
    NOT_STARTED("未开始", true),
    GENERATING("生成中", false),
    PARTIALLY_DONE("部分完成", true),
    GENERATED("已生成", true),
    CONFIRMED("已确认", true);

    private final String displayName;
    private final boolean manualSettable;

    StepStatus(String displayName, boolean manualSettable) {
        this.displayName = displayName;
        this.manualSettable = manualSettable;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Whether this status can be manually set by user via the workflow state modal.
     * GENERATING is a transient state only set by the system during AI generation.
     */
    public boolean isManualSettable() {
        return manualSettable;
    }
}
