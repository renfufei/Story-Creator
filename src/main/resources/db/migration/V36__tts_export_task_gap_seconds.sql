ALTER TABLE tts_export_tasks ADD COLUMN chunk_gap_seconds DOUBLE DEFAULT 0.1;
ALTER TABLE tts_export_tasks ADD COLUMN skip_gap_seconds DOUBLE DEFAULT 0.3;
