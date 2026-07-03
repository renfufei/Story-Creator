package com.storycreator.core.domain;

public enum MaterialCategory {
    CHARACTER("角色"),
    WORLD("世界观"),
    OUTLINE("大纲"),
    SKILL("金手指/技能"),
    ITEM("道具/武器"),
    OTHER("其他");

    private final String displayName;

    MaterialCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
