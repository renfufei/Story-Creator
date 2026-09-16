package com.storycreator.txtimport;

/**
 * 逆向工程的阶段定义（有序）。
 *
 * <p>整体流程：章节大纲与角色 → 故事弧线 → 世界观 / 角色汇总 / 故事总纲。
 * 每个阶段都可以独立完成、独立恢复；恢复时以「产出物是否已存在」为准判断完成度。
 */
public enum RePhase {

    /** 题材为空或其他时，由 AI 依据章节样本识别题材并写回项目（导入时用户未指定题材则执行）。 */
    GENRE("题材识别", 5),

    /** 逐章推断章节大纲 + 本章出场角色。 */
    CHAPTER_OUTLINE("章节大纲与角色", 10),

    /** 按每卷章节数分组，由各章大纲推断本卷故事弧线。 */
    STORY_ARC("故事弧线", 20),

    /** 由全部故事弧线汇总世界观设定。 */
    WORLD("世界观汇总", 30),

    /** 由全部故事弧线汇总主要角色信息。 */
    CHARACTERS("角色汇总", 40),

    /** 由角色汇总逐个生成独立角色卡（每张卡一次大模型调用）。 */
    CHARACTER_CARDS("角色卡片", 45),

    /** 由全部故事弧线汇总整部作品的故事总纲。 */
    STORY_OUTLINE("故事总纲", 50);

    private final String label;
    private final int sortOrder;

    RePhase(String label, int sortOrder) {
        this.label = label;
        this.sortOrder = sortOrder;
    }

    public String getLabel() { return label; }
    public int getSortOrder() { return sortOrder; }

    /** 阶段状态。 */
    public enum Status {
        /** 尚未开始（或已完成部分，等待继续）。 */
        PENDING,
        /** 正在执行。 */
        RUNNING,
        /** 全部完成。 */
        COMPLETED,
        /** 执行失败。 */
        FAILED,
        /** 按用户选项跳过（例如关闭了「反推世界观」）。 */
        SKIPPED
    }
}
