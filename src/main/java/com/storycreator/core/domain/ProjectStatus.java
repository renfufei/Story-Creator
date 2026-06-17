package com.storycreator.core.domain;

public enum ProjectStatus {
    NOT_STARTED("未开始"),
    IN_PROGRESS("创作中"),
    COMPLETED("已完本"),
    ABANDONED("已废弃");

    private final String displayName;

    ProjectStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
