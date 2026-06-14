package com.storycreator.core.domain;

public enum Genre {
    XUANHUAN("玄幻"),
    XIANXIA("仙侠"),
    DUSHI("都市"),
    LISHI("历史"),
    YANQING("言情"),
    KEHUAN("科幻"),
    XUANYI("悬疑"),
    QIHUAN("奇幻"),
    WUXIA("武侠"),
    OTHER("其他");

    private final String displayName;

    Genre(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
