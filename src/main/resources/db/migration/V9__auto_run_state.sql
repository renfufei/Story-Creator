ALTER TABLE projects ADD COLUMN auto_run_status VARCHAR(30) NOT NULL DEFAULT 'IDLE';
ALTER TABLE projects ADD COLUMN auto_run_step VARCHAR(50);
ALTER TABLE projects ADD COLUMN auto_run_chapter INT NOT NULL DEFAULT 0;
ALTER TABLE projects ADD COLUMN auto_run_error VARCHAR(500);
ALTER TABLE projects ADD COLUMN auto_run_progress VARCHAR(200);
