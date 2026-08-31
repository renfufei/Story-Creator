package com.storycreator.core.domain;

public enum WorldFacetKey {
    POWER_SYSTEM("力量体系"),
    WORLD_BACKGROUND("世界背景"),
    FACTION_MAP("势力格局"),
    CONFLICT_ROOTS("冲突根源");

    private final String displayName;

    WorldFacetKey(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
