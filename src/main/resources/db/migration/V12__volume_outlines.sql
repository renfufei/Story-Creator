-- Volume outlines table for story arc per volume
CREATE TABLE volume_outlines (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id     BIGINT NOT NULL,
    volume_number  INT NOT NULL,
    title          VARCHAR(200),
    arc_summary    TEXT,
    chapter_start  INT NOT NULL,
    chapter_end    INT NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, volume_number)
);

-- Add volume_number to chapter_outlines
ALTER TABLE chapter_outlines ADD COLUMN volume_number INT;
