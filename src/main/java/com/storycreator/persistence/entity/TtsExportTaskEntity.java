package com.storycreator.persistence.entity;

import com.storycreator.tts.TtsExportStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tts_export_tasks")
public class TtsExportTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "config_id", nullable = false)
    private Long configId;

    @Column(nullable = false, length = 100)
    private String voice = "alloy";

    @Column(nullable = false)
    private double speed = 1.0;

    @Column(name = "min_len", nullable = false)
    private int minLen = 30;

    @Column(name = "max_len", nullable = false)
    private int maxLen = 200;

    @Column(name = "use_ffmpeg", nullable = false)
    private boolean useFfmpeg = false;

    @Column(length = 10)
    private String bitrate = "128k";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TtsExportStatus status = TtsExportStatus.PENDING;

    @Column(name = "progress_chapter", nullable = false)
    private int progressChapter = 0;

    @Column(name = "progress_total_chapters", nullable = false)
    private int progressTotalChapters = 0;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

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

    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }

    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public int getMinLen() { return minLen; }
    public void setMinLen(int minLen) { this.minLen = minLen; }

    public int getMaxLen() { return maxLen; }
    public void setMaxLen(int maxLen) { this.maxLen = maxLen; }

    public boolean isUseFfmpeg() { return useFfmpeg; }
    public void setUseFfmpeg(boolean useFfmpeg) { this.useFfmpeg = useFfmpeg; }

    public String getBitrate() { return bitrate; }
    public void setBitrate(String bitrate) { this.bitrate = bitrate; }

    public TtsExportStatus getStatus() { return status; }
    public void setStatus(TtsExportStatus status) { this.status = status; }

    public int getProgressChapter() { return progressChapter; }
    public void setProgressChapter(int progressChapter) { this.progressChapter = progressChapter; }

    public int getProgressTotalChapters() { return progressTotalChapters; }
    public void setProgressTotalChapters(int progressTotalChapters) { this.progressTotalChapters = progressTotalChapters; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
