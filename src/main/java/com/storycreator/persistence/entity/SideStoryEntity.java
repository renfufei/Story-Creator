package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "side_stories")
public class SideStoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 30)
    private String type = "SUPPLEMENTARY";

    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

    @Column(name = "attached_volume")
    private Integer attachedVolume;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(columnDefinition = "TEXT")
    private String outline;

    @Column(name = "creative_guidance", columnDefinition = "TEXT")
    private String creativeGuidance;

    @Column(name = "arc_name", length = 100)
    private String arcName;

    @Column(name = "arc_summary", columnDefinition = "TEXT")
    private String arcSummary;

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

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getAttachedVolume() { return attachedVolume; }
    public void setAttachedVolume(Integer attachedVolume) { this.attachedVolume = attachedVolume; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getOutline() { return outline; }
    public void setOutline(String outline) { this.outline = outline; }

    public String getCreativeGuidance() { return creativeGuidance; }
    public void setCreativeGuidance(String creativeGuidance) { this.creativeGuidance = creativeGuidance; }

    public String getArcName() { return arcName; }
    public void setArcName(String arcName) { this.arcName = arcName; }

    public String getArcSummary() { return arcSummary; }
    public void setArcSummary(String arcSummary) { this.arcSummary = arcSummary; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
