package com.storycreator.web;

/**
 * Encapsulates the context parameters for prompt exploration.
 */
public class PromptExploreContext {
    private Long projectId;
    private Integer chapterNumber;
    private Long characterId;
    private Integer cardNumber;
    private Integer totalCards;
    private Integer volumeNumber;
    private Long templateId;
    private Long sideStoryId;
    private Integer sideStoryChapterNumber;

    public PromptExploreContext() {}

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Integer getChapterNumber() { return chapterNumber; }
    public void setChapterNumber(Integer chapterNumber) { this.chapterNumber = chapterNumber; }

    public Long getCharacterId() { return characterId; }
    public void setCharacterId(Long characterId) { this.characterId = characterId; }

    public Integer getCardNumber() { return cardNumber; }
    public void setCardNumber(Integer cardNumber) { this.cardNumber = cardNumber; }

    public Integer getTotalCards() { return totalCards; }
    public void setTotalCards(Integer totalCards) { this.totalCards = totalCards; }

    public Integer getVolumeNumber() { return volumeNumber; }
    public void setVolumeNumber(Integer volumeNumber) { this.volumeNumber = volumeNumber; }

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }

    public Long getSideStoryId() { return sideStoryId; }
    public void setSideStoryId(Long sideStoryId) { this.sideStoryId = sideStoryId; }

    public Integer getSideStoryChapterNumber() { return sideStoryChapterNumber; }
    public void setSideStoryChapterNumber(Integer sideStoryChapterNumber) { this.sideStoryChapterNumber = sideStoryChapterNumber; }
}
