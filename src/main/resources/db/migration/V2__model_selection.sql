-- Global default model setting
CREATE TABLE global_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(500)
);

INSERT INTO global_settings (setting_key, setting_value) VALUES ('default_model_config_id', '');

-- Project default model
ALTER TABLE projects ADD COLUMN default_model_config_id BIGINT;

-- Step-level model override (already exists as workflow_model_assignments, add model_config_id to workflow_states)
ALTER TABLE workflow_states ADD COLUMN model_config_id BIGINT;
