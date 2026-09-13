-- 灵感：记录某个项目创作过程中的灵感片段与想法，一个项目可有多条
CREATE TABLE inspirations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);
CREATE INDEX idx_inspirations_project ON inspirations(project_id);
