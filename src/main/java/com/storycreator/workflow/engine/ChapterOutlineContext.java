package com.storycreator.workflow.engine;

import java.util.List;

/**
 * Context for generating a single chapter outline.
 */
public class ChapterOutlineContext {

    private int chapterNum;
    private int totalChapters;
    private VolumeRange vol;
    private String volumeArc;
    private List<String> previousOutlines;
    private List<String> nextOutlines;

    public ChapterOutlineContext() {}

    // --- Getters ---

    public int getChapterNum() { return chapterNum; }
    public int getTotalChapters() { return totalChapters; }
    public VolumeRange getVol() { return vol; }
    public String getVolumeArc() { return volumeArc; }
    public List<String> getPreviousOutlines() { return previousOutlines; }
    public List<String> getNextOutlines() { return nextOutlines; }

    // --- Setters ---

    public void setChapterNum(int chapterNum) { this.chapterNum = chapterNum; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }
    public void setVol(VolumeRange vol) { this.vol = vol; }
    public void setVolumeArc(String volumeArc) { this.volumeArc = volumeArc; }
    public void setPreviousOutlines(List<String> previousOutlines) { this.previousOutlines = previousOutlines; }
    public void setNextOutlines(List<String> nextOutlines) { this.nextOutlines = nextOutlines; }
}
