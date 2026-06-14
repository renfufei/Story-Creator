package com.storycreator.persistence.entity;

import com.storycreator.core.domain.WorkflowStep;
import jakarta.persistence.*;

@Entity
@Table(name = "auto_run_step_configs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "step"})
})
public class AutoRunStepConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 50)
    private WorkflowStep step;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public AutoRunStepConfigEntity() {}

    public AutoRunStepConfigEntity(Long projectId, WorkflowStep step, boolean enabled) {
        this.projectId = projectId;
        this.step = step;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public WorkflowStep getStep() { return step; }
    public void setStep(WorkflowStep step) { this.step = step; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
