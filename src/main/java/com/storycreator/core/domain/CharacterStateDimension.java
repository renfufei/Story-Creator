package com.storycreator.core.domain;

public enum CharacterStateDimension {
    CULTIVATION_LEVEL("修为境界", true, 0),
    EQUIPMENT("宝物/装备", true, 1),
    LOCATION("地理位置", true, 2),
    RELATIONSHIPS("人物关系变化", true, 3),
    EMOTIONAL_STATE("情感状态", false, 4),
    PHYSICAL_CONDITION("伤势/身体状况", false, 5),
    SECRETS("秘密/隐藏信息", false, 6),
    GOALS("目标/动机变化", false, 7),
    FACTION("势力/阵营归属", false, 8),
    COMMITMENTS("重要承诺/约定", false, 9);

    private final String displayName;
    private final boolean defaultEnabled;
    private final int defaultSortOrder;

    CharacterStateDimension(String displayName, boolean defaultEnabled, int defaultSortOrder) {
        this.displayName = displayName;
        this.defaultEnabled = defaultEnabled;
        this.defaultSortOrder = defaultSortOrder;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public int getDefaultSortOrder() {
        return defaultSortOrder;
    }
}
