package com.storycreator.workflow.engine;

import java.util.List;

/**
 * Context for generating a single chapter outline.
 */
public record ChapterOutlineContext(
    int chapterNum,
    int totalChapters,
    VolumeRange vol,
    String volumeArc,
    List<String> previousOutlines,
    List<String> nextOutlines
) {}
