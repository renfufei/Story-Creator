-- Remove enhanced strategy mode: drop tables, columns no longer needed

-- Drop enhanced-only tables
DROP TABLE IF EXISTS writing_rules;
DROP TABLE IF EXISTS style_fingerprints;

-- Drop enhanced-only columns from chapters
ALTER TABLE chapters DROP COLUMN IF EXISTS writing_reasoning;
ALTER TABLE chapters DROP COLUMN IF EXISTS instant_review;
ALTER TABLE chapters DROP COLUMN IF EXISTS storyline_snapshot;
ALTER TABLE chapters DROP COLUMN IF EXISTS deep_review;
ALTER TABLE chapters DROP COLUMN IF EXISTS writing_cycle_status;

-- Drop enhanced-only column from characters
ALTER TABLE characters DROP COLUMN IF EXISTS behavior_boundaries;

-- Drop strategy column from projects (no longer needed)
ALTER TABLE projects DROP COLUMN IF EXISTS auto_run_strategy;
