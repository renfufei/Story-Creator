package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "proofreading_reports", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "chapter_number"}))
public class ProofreadingReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "chapter_number", nullable = false)
    private int chapterNumber;

    @Column(name = "plot_summary", length = 500)
    private String plotSummary;

    @Column(name = "character_issues", columnDefinition = "TEXT")
    private String characterIssues;

    @Column(name = "consistency_issues", columnDefinition = "TEXT")
    private String consistencyIssues;

    @Column(name = "continuity_issues", columnDefinition = "TEXT")
    private String continuityIssues;

    @Column(columnDefinition = "TEXT")
    private String foreshadowing;

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

    public String getPlotSummary() { return plotSummary; }
    public void setPlotSummary(String plotSummary) { this.plotSummary = plotSummary; }

    public String getCharacterIssues() { return characterIssues; }
    public void setCharacterIssues(String characterIssues) { this.characterIssues = characterIssues; }

    public String getConsistencyIssues() { return consistencyIssues; }
    public void setConsistencyIssues(String consistencyIssues) { this.consistencyIssues = consistencyIssues; }

    public String getContinuityIssues() { return continuityIssues; }
    public void setContinuityIssues(String continuityIssues) { this.continuityIssues = continuityIssues; }

    public String getForeshadowing() { return foreshadowing; }
    public void setForeshadowing(String foreshadowing) { this.foreshadowing = foreshadowing; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
