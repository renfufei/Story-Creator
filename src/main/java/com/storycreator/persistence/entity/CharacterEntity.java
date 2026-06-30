package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "characters")
public class CharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(length = 100)
    private String name;

    @Column(length = 50)
    private String role;

    @Column(length = 20)
    private String gender;

    @Column(length = 500)
    private String personality;

    @Column(length = 500)
    private String appearance;

    @Column(columnDefinition = "TEXT")
    private String background;

    @Column(length = 500)
    private String motivation;

    @Column(length = 500)
    private String relationships;

    @Column(length = 500)
    private String abilities;

    @Column(length = 20)
    private String age;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 20)
    private String status;

    @Column(name = "behavior_boundaries", columnDefinition = "TEXT")
    private String behaviorBoundaries;

    @Column(name = "image_prompt_template", length = 2000)
    private String imagePromptTemplate;

    @Column(name = "sort_order")
    private int sortOrder;

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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }

    public String getAppearance() { return appearance; }
    public void setAppearance(String appearance) { this.appearance = appearance; }

    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }

    public String getMotivation() { return motivation; }
    public void setMotivation(String motivation) { this.motivation = motivation; }

    public String getRelationships() { return relationships; }
    public void setRelationships(String relationships) { this.relationships = relationships; }

    public String getAbilities() { return abilities; }
    public void setAbilities(String abilities) { this.abilities = abilities; }

    public String getAge() { return age; }
    public void setAge(String age) { this.age = age; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBehaviorBoundaries() { return behaviorBoundaries; }
    public void setBehaviorBoundaries(String behaviorBoundaries) { this.behaviorBoundaries = behaviorBoundaries; }

    public String getImagePromptTemplate() { return imagePromptTemplate; }
    public void setImagePromptTemplate(String imagePromptTemplate) { this.imagePromptTemplate = imagePromptTemplate; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
