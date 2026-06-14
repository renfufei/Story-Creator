package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "volume_outlines", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "volume_number"}))
public class VolumeOutlineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "volume_number", nullable = false)
    private int volumeNumber;

    @Column(length = 200)
    private String title;

    @Column(name = "arc_summary", columnDefinition = "TEXT")
    private String arcSummary;

    @Column(name = "chapter_start", nullable = false)
    private int chapterStart;

    @Column(name = "chapter_end", nullable = false)
    private int chapterEnd;

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

    public int getVolumeNumber() { return volumeNumber; }
    public void setVolumeNumber(int volumeNumber) { this.volumeNumber = volumeNumber; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArcSummary() { return arcSummary; }
    public void setArcSummary(String arcSummary) { this.arcSummary = arcSummary; }

    public int getChapterStart() { return chapterStart; }
    public void setChapterStart(int chapterStart) { this.chapterStart = chapterStart; }

    public int getChapterEnd() { return chapterEnd; }
    public void setChapterEnd(int chapterEnd) { this.chapterEnd = chapterEnd; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
