package com.storycreator.ai.prompt;

public enum TemplateWorkflowTag {
    STANDARD("标准"),
    /** 章节扩写：/projects/{id}/expansion 手动触发，不属于自动运行的标准流程。 */
    EXPANSION("扩写"),
    IMAGE("图像"),
    SIDE_STORY("番外"),
    REVERSE("逆向");

    private final String displayName;

    TemplateWorkflowTag(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
