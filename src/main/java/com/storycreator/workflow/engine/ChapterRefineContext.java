package com.storycreator.workflow.engine;

import java.util.List;

/**
 * Context for refining a single chapter outline.
 */
public record ChapterRefineContext(
    int chapterNum,
    int totalChapters,
    String volumeArc,
    String currentChapterOutline,
    List<ChapterOutlineInfo> previousOutlines,
    List<ChapterOutlineInfo> nextOutlines,
    VolumeRange currentVolume
) {}
