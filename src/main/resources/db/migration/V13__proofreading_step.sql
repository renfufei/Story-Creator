-- 章节新增校对相关字段
ALTER TABLE chapters ADD COLUMN proofread_status VARCHAR(30) DEFAULT 'NOT_STARTED';
ALTER TABLE chapters ADD COLUMN plot_summary VARCHAR(500);

-- 校对报告表（每章一条，存储各子步骤结果）
CREATE TABLE proofreading_reports (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id        BIGINT NOT NULL,
    chapter_number    INT NOT NULL,
    plot_summary      VARCHAR(500),
    character_issues  TEXT,
    consistency_issues TEXT,
    continuity_issues TEXT,
    foreshadowing     TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, chapter_number)
);
