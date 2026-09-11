ALTER TABLE txt_import_jobs
    ADD COLUMN chapters_per_volume INT NOT NULL DEFAULT 30;
