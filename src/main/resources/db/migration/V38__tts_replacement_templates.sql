CREATE TABLE tts_replacement_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tts_replacement_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES tts_replacement_templates(id) ON DELETE CASCADE,
    pattern VARCHAR(1000) NOT NULL,
    replacement VARCHAR(500) NOT NULL DEFAULT '',
    is_regex BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(200),
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tts_model_template_bindings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_config_id BIGINT NOT NULL REFERENCES ai_model_configs(id) ON DELETE CASCADE,
    template_ref VARCHAR(200) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (model_config_id, template_ref)
);
