package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "txt_import_jobs")
public class TxtImportJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 50)
    private String genre;

    @Column(nullable = false, length = 30)
    private String status = "PENDING";

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "progress_note", length = 500)
    private String progressNote;

    @Column(name = "chapter_count", nullable = false)
    private int chapterCount = 0;

    @Column(name = "total_word_count", nullable = false)
    private int totalWordCount = 0;

    @Column(name = "raw_content", columnDefinition = "TEXT")
    private String rawContent;

    @Column(name = "run_world_building", nullable = false)
    private boolean runWorldBuilding = true;

    @Column(name = "run_characters", nullable = false)
    private boolean runCharacters = true;

    @Column(name = "run_outline", nullable = false)
    private boolean runOutline = true;

    @Column(name = "sampling_strategy", nullable = false, length = 30)
    private String samplingStrategy = "UNIFORM";

    @Column(name = "sampling_n", nullable = false)
    private int samplingN = 10;

    @Column(name = "model_config_id")
    private Long modelConfigId;

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

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getProgressNote() { return progressNote; }
    public void setProgressNote(String progressNote) { this.progressNote = progressNote; }

    public int getChapterCount() { return chapterCount; }
    public void setChapterCount(int chapterCount) { this.chapterCount = chapterCount; }

    public int getTotalWordCount() { return totalWordCount; }
    public void setTotalWordCount(int totalWordCount) { this.totalWordCount = totalWordCount; }

    public String getRawContent() { return rawContent; }
    public void setRawContent(String rawContent) { this.rawContent = rawContent; }

    public boolean isRunWorldBuilding() { return runWorldBuilding; }
    public void setRunWorldBuilding(boolean runWorldBuilding) { this.runWorldBuilding = runWorldBuilding; }

    public boolean isRunCharacters() { return runCharacters; }
    public void setRunCharacters(boolean runCharacters) { this.runCharacters = runCharacters; }

    public boolean isRunOutline() { return runOutline; }
    public void setRunOutline(boolean runOutline) { this.runOutline = runOutline; }

    public String getSamplingStrategy() { return samplingStrategy; }
    public void setSamplingStrategy(String samplingStrategy) { this.samplingStrategy = samplingStrategy; }

    public int getSamplingN() { return samplingN; }
    public void setSamplingN(int samplingN) { this.samplingN = samplingN; }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
