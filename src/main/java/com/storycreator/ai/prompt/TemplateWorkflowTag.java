package com.storycreator.ai.prompt;

public enum TemplateWorkflowTag {
    STANDARD("标准"),
    IMAGE("图像"),
    SIDE_STORY("番外");

    private final String displayName;

    TemplateWorkflowTag(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
