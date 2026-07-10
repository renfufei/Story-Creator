package com.storycreator.workflow.engine;

/**
 * Structured info for a chapter outline used during refine context building.
 */
public record ChapterOutlineInfo(
    int chapterNumber,
    String title,
    String characterNames,
    String summary,
    boolean fullContent
) {}
