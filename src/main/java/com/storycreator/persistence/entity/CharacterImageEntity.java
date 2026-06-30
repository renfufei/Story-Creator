package com.storycreator.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "character_images")
public class CharacterImageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "image_type", nullable = false, length = 20)
    private String imageType;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "image_prompt", length = 2000)
    private String imagePrompt;

    @Column(name = "source_prompt", length = 2000)
    private String sourcePrompt;

    @Column(name = "model_config_id")
    private Long modelConfigId;

    @Column(name = "image_config_id")
    private Long imageConfigId;

    @Column(name = "text_config_id")
    private Long textConfigId;

    @Column(nullable = false, length = 20)
    private String status = "PROMPT_PENDING";

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

    public Long getCharacterId() { return characterId; }
    public void setCharacterId(Long characterId) { this.characterId = characterId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getImageType() { return imageType; }
    public void setImageType(String imageType) { this.imageType = imageType; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getImagePrompt() { return imagePrompt; }
    public void setImagePrompt(String imagePrompt) { this.imagePrompt = imagePrompt; }

    public String getSourcePrompt() { return sourcePrompt; }
    public void setSourcePrompt(String sourcePrompt) { this.sourcePrompt = sourcePrompt; }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public Long getImageConfigId() { return imageConfigId; }
    public void setImageConfigId(Long imageConfigId) { this.imageConfigId = imageConfigId; }

    public Long getTextConfigId() { return textConfigId; }
    public void setTextConfigId(Long textConfigId) { this.textConfigId = textConfigId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
