-- Add total_chapters to projects
ALTER TABLE projects ADD COLUMN total_chapters INT NOT NULL DEFAULT 10;
