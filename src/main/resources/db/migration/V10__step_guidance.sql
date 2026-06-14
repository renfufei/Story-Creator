CREATE TABLE step_guidances (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    step VARCHAR(50) NOT NULL,
    guidance TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_step_guidance UNIQUE (project_id, step)
);

-- Append {{stepGuidance}} placeholder to all existing prompt templates
UPDATE prompt_templates SET template = template || '
{{stepGuidance}}';
