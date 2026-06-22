package com.storycreator.persistence.entity;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ProjectStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.workflow.autorun.AutoRunStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Genre genre;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 50)
    private WorkflowStep currentStep = WorkflowStep.WORLD_BUILDING;

    @Column(name = "total_chapters", nullable = false)
    private int totalChapters = 10;

    @Column(name = "chapter_word_count", nullable = false)
    private int chapterWordCount = 5000;

    @Column(name = "chapter_word_count_min", nullable = false)
    private int chapterWordCountMin = 4000;

    @Column(name = "chapter_word_count_max", nullable = false)
    private int chapterWordCountMax = 6000;

    @Column(name = "character_count", nullable = false)
    private int characterCount = 5;

    @Column(name = "chapters_per_volume", nullable = false)
    private int chaptersPerVolume = 10;

    @Column(name = "default_model_config_id")
    private Long defaultModelConfigId;

    @Column(name = "auto_mode", nullable = false)
    private boolean autoMode = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.IN_PROGRESS;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_run_status", nullable = false, length = 30)
    private AutoRunStatus autoRunStatus = AutoRunStatus.IDLE;

    @Column(name = "auto_run_step", length = 50)
    private String autoRunStep;

    @Column(name = "auto_run_chapter", nullable = false)
    private int autoRunChapter = 0;

    @Column(name = "auto_run_error", length = 500)
    private String autoRunError;

    @Column(name = "auto_run_progress", length = 200)
    private String autoRunProgress;

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

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Genre getGenre() { return genre; }
    public void setGenre(Genre genre) { this.genre = genre; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public WorkflowStep getCurrentStep() { return currentStep; }
    public void setCurrentStep(WorkflowStep currentStep) { this.currentStep = currentStep; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

    public int getChapterWordCount() { return chapterWordCount; }
    public void setChapterWordCount(int chapterWordCount) { this.chapterWordCount = chapterWordCount; }

    public int getChapterWordCountMin() { return chapterWordCountMin; }
    public void setChapterWordCountMin(int chapterWordCountMin) { this.chapterWordCountMin = chapterWordCountMin; }

    public int getChapterWordCountMax() { return chapterWordCountMax; }
    public void setChapterWordCountMax(int chapterWordCountMax) { this.chapterWordCountMax = chapterWordCountMax; }

    public int getCharacterCount() { return characterCount; }
    public void setCharacterCount(int characterCount) { this.characterCount = characterCount; }

    public int getChaptersPerVolume() { return chaptersPerVolume; }
    public void setChaptersPerVolume(int chaptersPerVolume) { this.chaptersPerVolume = chaptersPerVolume; }

    public Long getDefaultModelConfigId() { return defaultModelConfigId; }
    public void setDefaultModelConfigId(Long defaultModelConfigId) { this.defaultModelConfigId = defaultModelConfigId; }

    public boolean isAutoMode() { return autoMode; }
    public void setAutoMode(boolean autoMode) { this.autoMode = autoMode; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    public AutoRunStatus getAutoRunStatus() { return autoRunStatus; }
    public void setAutoRunStatus(AutoRunStatus autoRunStatus) { this.autoRunStatus = autoRunStatus; }

    public String getAutoRunStep() { return autoRunStep; }
    public void setAutoRunStep(String autoRunStep) { this.autoRunStep = autoRunStep; }

    public int getAutoRunChapter() { return autoRunChapter; }
    public void setAutoRunChapter(int autoRunChapter) { this.autoRunChapter = autoRunChapter; }

    public String getAutoRunError() { return autoRunError; }
    public void setAutoRunError(String autoRunError) { this.autoRunError = autoRunError; }

    public String getAutoRunProgress() { return autoRunProgress; }
    public void setAutoRunProgress(String autoRunProgress) { this.autoRunProgress = autoRunProgress; }
}
