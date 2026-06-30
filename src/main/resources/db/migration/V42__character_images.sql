CREATE TABLE character_images (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    character_id    BIGINT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
    project_id      BIGINT NOT NULL,
    image_type      VARCHAR(20) NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    image_prompt    VARCHAR(2000),
    source_prompt   VARCHAR(2000),
    model_config_id BIGINT,
    status          VARCHAR(20) NOT NULL DEFAULT 'READY',
    error_message   VARCHAR(500),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_char_images_char ON character_images(character_id);
CREATE INDEX idx_char_images_proj ON character_images(project_id);
