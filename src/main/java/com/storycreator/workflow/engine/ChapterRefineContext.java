package com.storycreator.workflow.engine;

import java.util.List;

/**
 * Context for refining a single chapter outline.
 */
public class ChapterRefineContext {

    private int chapterNum;
    private int totalChapters;
    private String volumeArc;
    private String currentChapterOutline;
    private List<ChapterOutlineInfo> previousOutlines;
    private List<ChapterOutlineInfo> nextOutlines;
    private VolumeRange currentVolume;

    public ChapterRefineContext() {}

    // --- Getters ---

    public int getChapterNum() { return chapterNum; }
    public int getTotalChapters() { return totalChapters; }
    public String getVolumeArc() { return volumeArc; }
    public String getCurrentChapterOutline() { return currentChapterOutline; }
    public List<ChapterOutlineInfo> getPreviousOutlines() { return previousOutlines; }
    public List<ChapterOutlineInfo> getNextOutlines() { return nextOutlines; }
    public VolumeRange getCurrentVolume() { return currentVolume; }

    // --- Setters ---

    public void setChapterNum(int chapterNum) { this.chapterNum = chapterNum; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }
    public void setVolumeArc(String volumeArc) { this.volumeArc = volumeArc; }
    public void setCurrentChapterOutline(String currentChapterOutline) { this.currentChapterOutline = currentChapterOutline; }
    public void setPreviousOutlines(List<ChapterOutlineInfo> previousOutlines) { this.previousOutlines = previousOutlines; }
    public void setNextOutlines(List<ChapterOutlineInfo> nextOutlines) { this.nextOutlines = nextOutlines; }
    public void setCurrentVolume(VolumeRange currentVolume) { this.currentVolume = currentVolume; }
}
