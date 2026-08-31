-- Chapter split configs (builtin + user custom)
CREATE TABLE chapter_split_configs (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    pattern       VARCHAR(1000) NOT NULL,
    title_group   INT NOT NULL DEFAULT 0,
    include_match BOOLEAN NOT NULL DEFAULT FALSE,
    is_builtin    BOOLEAN NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order    INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- TXT import job
CREATE TABLE txt_import_jobs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id          BIGINT,
    title               VARCHAR(200) NOT NULL,
    genre               VARCHAR(50),
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message       VARCHAR(1000),
    progress_note       VARCHAR(500),
    chapter_count       INT NOT NULL DEFAULT 0,
    total_word_count    INT NOT NULL DEFAULT 0,
    raw_content         TEXT,
    run_world_building  BOOLEAN NOT NULL DEFAULT TRUE,
    run_characters      BOOLEAN NOT NULL DEFAULT TRUE,
    run_outline         BOOLEAN NOT NULL DEFAULT TRUE,
    sampling_strategy   VARCHAR(30) NOT NULL DEFAULT 'UNIFORM',
    sampling_n          INT NOT NULL DEFAULT 10,
    model_config_id     BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_txt_import_jobs_project ON txt_import_jobs(project_id);

-- TXT import chapters preview
CREATE TABLE txt_import_chapters (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id         BIGINT NOT NULL,
    chapter_number INT NOT NULL,
    title          VARCHAR(300),
    content        TEXT,
    word_count     INT NOT NULL DEFAULT 0,
    sort_order     INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_id, chapter_number)
);
CREATE INDEX idx_txt_import_chapters_job ON txt_import_chapters(job_id);
