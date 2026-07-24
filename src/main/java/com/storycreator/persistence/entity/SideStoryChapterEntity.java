package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "side_story_chapters", uniqueConstraints = @UniqueConstraint(columnNames = {"side_story_id", "chapter_number"}))
public class SideStoryChapterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "side_story_id", nullable = false)
    private Long sideStoryId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "chapter_number", nullable = false)
    private int chapterNumber;

    @Column(length = 200)
    private String title;

    @Column(name = "outline_summary", columnDefinition = "TEXT")
    private String outlineSummary;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "word_count", nullable = false)
    private int wordCount;

    @Column(nullable = false, length = 30)
    private String status = "NOT_STARTED";

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

    public Long getSideStoryId() { return sideStoryId; }
    public void setSideStoryId(Long sideStoryId) { this.sideStoryId = sideStoryId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public int getChapterNumber() { return chapterNumber; }
    public void setChapterNumber(int chapterNumber) { this.chapterNumber = chapterNumber; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getOutlineSummary() { return outlineSummary; }
    public void setOutlineSummary(String outlineSummary) { this.outlineSummary = outlineSummary; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getWordCount() { return wordCount; }
    public void setWordCount(int wordCount) { this.wordCount = wordCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
