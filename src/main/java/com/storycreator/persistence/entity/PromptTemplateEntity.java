package com.storycreator.persistence.entity;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "prompt_templates")
public class PromptTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WorkflowStep step;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private Genre genre;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String template;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "default_template", columnDefinition = "TEXT")
    private String defaultTemplate;

    @Column(name = "default_system_prompt", columnDefinition = "TEXT")
    private String defaultSystemPrompt;

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

    public WorkflowStep getStep() { return step; }
    public void setStep(WorkflowStep step) { this.step = step; }

    public Genre getGenre() { return genre; }
    public void setGenre(Genre genre) { this.genre = genre; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public String getDefaultTemplate() { return defaultTemplate; }
    public void setDefaultTemplate(String defaultTemplate) { this.defaultTemplate = defaultTemplate; }

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
    public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }
}
