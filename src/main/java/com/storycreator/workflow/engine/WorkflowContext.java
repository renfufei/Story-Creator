package com.storycreator.workflow.engine;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;

import java.util.HashMap;
import java.util.Map;

public class WorkflowContext {

    private Long projectId;
    private String title;
    private Genre genre;
    private String description;
    private WorkflowStep currentStep;
    private String worldSetting;
    private String characters;
    private String outline;
    private int currentChapter;
    private int totalChapters;
    private int chapterWordCount = 5000;
    private int chapterWordCountMin = 4000;
    private int chapterWordCountMax = 6000;
    private String chapterTitle;
    private String chapterSummary;
    private String previousChapterContent;
    private String nextChapterTitle;
    private String nextChapterSummary;
    private String overallOutline;
    private String storySummary;
    private String contentToPolish;
    private String polishNote;
    private String characterCards;
    private String stepGuidance;
    private String previousCharacterStates;
    private String referenceMaterials;
    /**
     * 本章所属分卷（0 = 未知 / 项目没有分卷）。生成前由 {@code VolumeService} 定下来，
     * 只作为<b>调用参数</b>随请求走（见 {@code AiRequest#getVolumeNumber()}），不写进提示词。
     */
    private int volumeNumber;
    private String volumeTitle;

    public Map<String, String> toTemplateVariables() {
        Map<String, String> vars = new HashMap<>();
        vars.put("title", title != null ? title : "");
        vars.put("genre", genre != null ? genre.getDisplayName() : "");
        vars.put("description", description != null ? description : "");
        vars.put("worldSetting", worldSetting != null ? worldSetting : "");
        vars.put("characters", characters != null ? characters : "");
        vars.put("outline", outline != null ? outline : "");
        vars.put("chapterNumber", String.valueOf(currentChapter));
        vars.put("totalChapters", String.valueOf(totalChapters));
        vars.put("chapterTitle", chapterTitle != null ? chapterTitle : "");
        vars.put("chapterSummary", chapterSummary != null ? chapterSummary : "");
        vars.put("chapterWordCount", String.valueOf(chapterWordCount));
        vars.put("chapterWordCountMin", String.valueOf(chapterWordCountMin));
        vars.put("chapterWordCountMax", String.valueOf(chapterWordCountMax));
        vars.put("previousContext", previousChapterContent != null ?
                "【前文回顾】\n" + previousChapterContent : "");
        vars.put("nextChapterTitle", nextChapterTitle != null ? nextChapterTitle : "");
        vars.put("nextChapterSummary", nextChapterSummary != null ? nextChapterSummary : "");
        vars.put("overallOutline", overallOutline != null ? overallOutline : "");
        vars.put("content", contentToPolish != null ? contentToPolish : "");
        vars.put("polishNote", polishNote != null ? polishNote : "");
        vars.put("characterCards", characterCards != null && !characterCards.isBlank()
                ? "【本章涉及角色详情】\n" + characterCards : "");
        vars.put("stepGuidance", stepGuidance != null && !stepGuidance.isBlank()
                ? "【创作指导】\n" + stepGuidance + "\n请在生成时参考以上指导意见。" : "");
        vars.put("previousCharacterStates", previousCharacterStates != null && !previousCharacterStates.isBlank()
                ? "【前章角色状态】\n" + previousCharacterStates : "");
        vars.put("referenceMaterials", referenceMaterials != null && !referenceMaterials.isBlank()
                ? referenceMaterials : "");
        return vars;
    }

    // Getters and Setters
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Genre getGenre() { return genre; }
    public void setGenre(Genre genre) { this.genre = genre; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public WorkflowStep getCurrentStep() { return currentStep; }
    public void setCurrentStep(WorkflowStep currentStep) { this.currentStep = currentStep; }

    public String getWorldSetting() { return worldSetting; }
    public void setWorldSetting(String worldSetting) { this.worldSetting = worldSetting; }

    public String getCharacters() { return characters; }
    public void setCharacters(String characters) { this.characters = characters; }

    public String getOutline() { return outline; }
    public void setOutline(String outline) { this.outline = outline; }

    public int getCurrentChapter() { return currentChapter; }
    public void setCurrentChapter(int currentChapter) { this.currentChapter = currentChapter; }

    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

    public int getChapterWordCount() { return chapterWordCount; }
    public void setChapterWordCount(int chapterWordCount) { this.chapterWordCount = chapterWordCount; }

    public int getChapterWordCountMin() { return chapterWordCountMin; }
    public void setChapterWordCountMin(int chapterWordCountMin) { this.chapterWordCountMin = chapterWordCountMin; }

    public int getChapterWordCountMax() { return chapterWordCountMax; }
    public void setChapterWordCountMax(int chapterWordCountMax) { this.chapterWordCountMax = chapterWordCountMax; }

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }

    public String getChapterSummary() { return chapterSummary; }
    public void setChapterSummary(String chapterSummary) { this.chapterSummary = chapterSummary; }

    public String getPreviousChapterContent() { return previousChapterContent; }
    public void setPreviousChapterContent(String previousChapterContent) { this.previousChapterContent = previousChapterContent; }

    public String getNextChapterTitle() { return nextChapterTitle; }
    public void setNextChapterTitle(String nextChapterTitle) { this.nextChapterTitle = nextChapterTitle; }

    public String getNextChapterSummary() { return nextChapterSummary; }
    public void setNextChapterSummary(String nextChapterSummary) { this.nextChapterSummary = nextChapterSummary; }

    public String getOverallOutline() { return overallOutline; }
    public void setOverallOutline(String overallOutline) { this.overallOutline = overallOutline; }

    public String getStorySummary() { return storySummary; }
    public void setStorySummary(String storySummary) { this.storySummary = storySummary; }

    public String getContentToPolish() { return contentToPolish; }
    public void setContentToPolish(String contentToPolish) { this.contentToPolish = contentToPolish; }

    public String getPolishNote() { return polishNote; }
    public void setPolishNote(String polishNote) { this.polishNote = polishNote; }

    public String getCharacterCards() { return characterCards; }
    public void setCharacterCards(String characterCards) { this.characterCards = characterCards; }

    public String getStepGuidance() { return stepGuidance; }
    public void setStepGuidance(String stepGuidance) { this.stepGuidance = stepGuidance; }

    public String getPreviousCharacterStates() { return previousCharacterStates; }
    public void setPreviousCharacterStates(String previousCharacterStates) { this.previousCharacterStates = previousCharacterStates; }

    public String getReferenceMaterials() { return referenceMaterials; }
    public void setReferenceMaterials(String referenceMaterials) { this.referenceMaterials = referenceMaterials; }

    public int getVolumeNumber() { return volumeNumber; }
    public void setVolumeNumber(int volumeNumber) { this.volumeNumber = volumeNumber; }

    public String getVolumeTitle() { return volumeTitle; }
    public void setVolumeTitle(String volumeTitle) { this.volumeTitle = volumeTitle; }

    /**
     * 把「项目 / 第几章 / 哪一卷」写进这次 AI 调用请求的参数里。
     *
     * <p>只是元数据：<b>不改提示词</b>，也不会发往模型服务端。目的是让返回内容天然带着卷归属，
     * 接受/落库时不用再靠章号整除去猜。</p>
     */
    public AiRequest stampScope(AiRequest request) {
        if (request == null) return null;
        return request.applyScope(projectId, currentChapter, volumeNumber, volumeTitle);
    }
}
