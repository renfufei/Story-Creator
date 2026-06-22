CREATE TABLE character_state_dimensions (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    dim_key     VARCHAR(50) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, dim_key)
);
