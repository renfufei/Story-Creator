package com.storycreator.persistence.entity;

import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_states", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "step"}))
public class WorkflowStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WorkflowStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StepStatus status = StepStatus.NOT_STARTED;

    @Column(name = "generated_content", columnDefinition = "TEXT")
    private String generatedContent;

    @Column(name = "user_edited_content", columnDefinition = "TEXT")
    private String userEditedContent;

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

    public WorkflowStep getStep() { return step; }
    public void setStep(WorkflowStep step) { this.step = step; }

    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }

    public String getGeneratedContent() { return generatedContent; }
    public void setGeneratedContent(String generatedContent) { this.generatedContent = generatedContent; }

    public String getUserEditedContent() { return userEditedContent; }
    public void setUserEditedContent(String userEditedContent) { this.userEditedContent = userEditedContent; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public String getEffectiveContent() {
        return userEditedContent != null ? userEditedContent : generatedContent;
    }
}
