-- Add ON DELETE CASCADE to FK constraints that were originally missing it.
-- Affected tables: tts_export_tasks.project_id and chat_messages.session_id.
-- H2 does not let us reliably DROP an unnamed constraint, so we rebuild the
-- tables with data preservation. Referential integrity is disabled during
-- the rebuild so the intermediate drops/inserts do not trip the FK checks.

SET REFERENTIAL_INTEGRITY FALSE;

-- ====================================================================
-- tts_export_tasks (+ dependent tts_export_chapters)
-- ====================================================================
CREATE TABLE tts_export_tasks_bak AS SELECT * FROM tts_export_tasks;
CREATE TABLE tts_export_chapters_bak AS SELECT * FROM tts_export_chapters;

DROP TABLE IF EXISTS tts_export_chapters;
DROP TABLE IF EXISTS tts_export_tasks;

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
    audio_format            VARCHAR(10) DEFAULT 'mp3' NOT NULL,
    chunk_gap_seconds       DOUBLE DEFAULT 0.1,
    skip_gap_seconds        DOUBLE DEFAULT 0.3,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress_chapter        INT NOT NULL DEFAULT 0,
    progress_total_chapters INT NOT NULL DEFAULT 0,
    error_message           VARCHAR(500),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
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

INSERT INTO tts_export_tasks (
    id, project_id, config_id, voice, speed, min_len, max_len,
    use_ffmpeg, bitrate, audio_format, chunk_gap_seconds, skip_gap_seconds,
    status, progress_chapter, progress_total_chapters, error_message,
    created_at, updated_at
)
SELECT
    id, project_id, config_id, voice, speed, min_len, max_len,
    use_ffmpeg, bitrate, audio_format, chunk_gap_seconds, skip_gap_seconds,
    status, progress_chapter, progress_total_chapters, error_message,
    created_at, updated_at
FROM tts_export_tasks_bak;

INSERT INTO tts_export_chapters (
    id, task_id, project_id, chapter_number, file_path, status,
    file_size, error_message, created_at, updated_at
)
SELECT
    id, task_id, project_id, chapter_number, file_path, status,
    file_size, error_message, created_at, updated_at
FROM tts_export_chapters_bak;

DROP TABLE tts_export_chapters_bak;
DROP TABLE tts_export_tasks_bak;

-- ====================================================================
-- chat_sessions / chat_messages
-- ====================================================================
CREATE TABLE chat_sessions_bak AS SELECT * FROM chat_sessions;
CREATE TABLE chat_messages_bak AS SELECT * FROM chat_messages;

DROP TABLE IF EXISTS chat_messages;
DROP TABLE IF EXISTS chat_sessions;

CREATE TABLE chat_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    model_config_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    model_config_id BIGINT,
    model_type VARCHAR(20),
    media_file_path VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

INSERT INTO chat_sessions (id, title, model_config_id, created_at, updated_at)
SELECT id, title, model_config_id, created_at, updated_at
FROM chat_sessions_bak;

INSERT INTO chat_messages (id, session_id, role, content, model_config_id, model_type, media_file_path, created_at)
SELECT id, session_id, role, content, model_config_id, model_type, media_file_path, created_at
FROM chat_messages_bak;

CREATE INDEX idx_chat_messages_session ON chat_messages(session_id);

DROP TABLE chat_messages_bak;
DROP TABLE chat_sessions_bak;

SET REFERENTIAL_INTEGRITY TRUE;
