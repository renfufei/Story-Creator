CREATE TABLE ai_usage_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    provider_name VARCHAR(50) NOT NULL,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, model_id)
);
