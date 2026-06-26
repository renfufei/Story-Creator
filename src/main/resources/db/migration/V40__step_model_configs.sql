CREATE TABLE step_model_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    step VARCHAR(50) NOT NULL,
    model_config_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_step_model_config UNIQUE (project_id, step)
);

-- Migrate existing data from workflow_states
INSERT INTO step_model_configs (project_id, step, model_config_id)
SELECT project_id, step, model_config_id FROM workflow_states WHERE model_config_id IS NOT NULL;
