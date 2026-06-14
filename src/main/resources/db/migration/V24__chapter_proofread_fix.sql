ALTER TABLE chapters ADD COLUMN content_before_fix TEXT;
ALTER TABLE chapters ADD COLUMN proofread_fix_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED';
