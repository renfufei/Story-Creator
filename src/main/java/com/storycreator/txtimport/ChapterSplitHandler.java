package com.storycreator.txtimport;

import java.util.List;

/**
 * 章节分割 Handler 接口。
 *
 * 设计目标：把「一种类型的 TXT 分割方式」封装成一个实现类，方便后期扩展。
 * 调度器（TxtChapterSplitter）会按顺序遍历所有 Handler：
 *  1. 调用 {@link #canHandle(String)} 让每个 Handler 自行判断能否处理该文本；
 *  2. 第一个返回 true 的 Handler 通过 {@link #split(String)} 完成分割。
 *
 * 每个 Handler 通过 {@link #getConfigName()} 绑定到数据库中的一条
 * {@code chapter_split_configs} 配置（按 name 匹配），从而复用其启用/排序/自定义正则等设置。
 */
public interface ChapterSplitHandler {

    /**
     * 绑定的配置名称，需与 {@code chapter_split_configs.name} 一致。
     */
    String getConfigName();

    /**
     * 判断当前 Handler 能否处理该文本（通常用于判断其正则是否至少命中一次）。
     */
    boolean canHandle(String text);

    /**
     * 执行分割，返回章节列表。调用方应先通过 {@link #canHandle(String)} 确认可处理。
     */
    List<SplitChapter> split(String text);
}
