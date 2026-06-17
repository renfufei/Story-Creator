package com.storycreator.persistence.entity;

import com.storycreator.core.domain.StepStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chapters", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "chapter_number"}))
public class ChapterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "chapter_number", nullable = false)
    private int chapterNumber;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_draft", columnDefinition = "TEXT")
    private String contentDraft;

    @Column(name = "word_count")
    private int wordCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StepStatus status = StepStatus.NOT_STARTED;

    @Column(name = "polish_note", length = 500)
    private String polishNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "polish_status", length = 30)
    private StepStatus polishStatus = StepStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "proofread_status", length = 30)
    private StepStatus proofreadStatus = StepStatus.NOT_STARTED;

    @Column(name = "content_before_fix", columnDefinition = "TEXT")
    private String contentBeforeFix;

    @Enumerated(EnumType.STRING)
    @Column(name = "proofread_fix_status", length = 30)
    private StepStatus proofreadFixStatus = StepStatus.NOT_STARTED;

    @Column(name = "character_states", columnDefinition = "TEXT")
    private String characterStates;

    @Column(name = "plot_summary", length = 500)
    private String plotSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public int getChapterNumber() { return chapterNumber; }
    public void setChapterNumber(int chapterNumber) { this.chapterNumber = chapterNumber; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentDraft() { return contentDraft; }
    public void setContentDraft(String contentDraft) { this.contentDraft = contentDraft; }

    public int getWordCount() { return wordCount; }
    public void setWordCount(int wordCount) { this.wordCount = wordCount; }

    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }

    public String getPolishNote() { return polishNote; }
    public void setPolishNote(String polishNote) { this.polishNote = polishNote; }

    public StepStatus getPolishStatus() { return polishStatus; }
    public void setPolishStatus(StepStatus polishStatus) { this.polishStatus = polishStatus; }

    public StepStatus getProofreadStatus() { return proofreadStatus; }
    public void setProofreadStatus(StepStatus proofreadStatus) { this.proofreadStatus = proofreadStatus; }

    public String getContentBeforeFix() { return contentBeforeFix; }
    public void setContentBeforeFix(String contentBeforeFix) { this.contentBeforeFix = contentBeforeFix; }

    public StepStatus getProofreadFixStatus() { return proofreadFixStatus; }
    public void setProofreadFixStatus(StepStatus proofreadFixStatus) { this.proofreadFixStatus = proofreadFixStatus; }

    public String getCharacterStates() { return characterStates; }
    public void setCharacterStates(String characterStates) { this.characterStates = characterStates; }

    public String getPlotSummary() { return plotSummary; }
    public void setPlotSummary(String plotSummary) { this.plotSummary = plotSummary; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
