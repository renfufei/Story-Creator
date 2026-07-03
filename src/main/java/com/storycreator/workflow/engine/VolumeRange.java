package com.storycreator.workflow.engine;

/**
 * Represents a volume's chapter range within the story.
 */
public record VolumeRange(int volumeNumber, int chapterStart, int chapterEnd) {}
