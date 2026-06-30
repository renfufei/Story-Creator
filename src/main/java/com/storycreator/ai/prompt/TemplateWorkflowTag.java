package com.storycreator.ai.prompt;

public enum TemplateWorkflowTag {
    STANDARD("标准"),
    ENHANCED("增强"),
    IMAGE("图像");

    private final String displayName;

    TemplateWorkflowTag(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
