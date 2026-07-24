CREATE TABLE learn_audio_files (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    module       VARCHAR(50) NOT NULL,
    item_key     VARCHAR(50) NOT NULL,
    text_content VARCHAR(200) NOT NULL,
    file_path    VARCHAR(500),
    config_id    BIGINT,
    voice        VARCHAR(100),
    speed        DOUBLE NOT NULL DEFAULT 1.0,
    format       VARCHAR(10) NOT NULL DEFAULT 'mp3',
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(500),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (module, item_key)
);
CREATE INDEX idx_learn_audio_module ON learn_audio_files(module);
