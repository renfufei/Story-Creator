CREATE TABLE tts_export_tasks (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    config_id               BIGINT NOT NULL,
    voice                   VARCHAR(100) NOT NULL DEFAULT 'alloy',
    speed                   DOUBLE NOT NULL DEFAULT 1.0,
    min_len                 INT NOT NULL DEFAULT 30,
    max_len                 INT NOT NULL DEFAULT 200,
    use_ffmpeg              BOOLEAN NOT NULL DEFAULT FALSE,
    bitrate                 VARCHAR(10) DEFAULT '128k',
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress_chapter        INT NOT NULL DEFAULT 0,
    progress_total_chapters INT NOT NULL DEFAULT 0,
    error_message           VARCHAR(500),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE tts_export_chapters (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    chapter_number  INT NOT NULL,
    file_path       VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    file_size       BIGINT,
    error_message   VARCHAR(300),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES tts_export_tasks(id) ON DELETE CASCADE,
    UNIQUE (task_id, chapter_number)
);
