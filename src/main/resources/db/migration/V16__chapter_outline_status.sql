ALTER TABLE chapter_outlines ADD COLUMN status VARCHAR(20) DEFAULT 'PENDING';

-- Mark existing records with content as COMPLETED
UPDATE chapter_outlines SET status = 'COMPLETED' WHERE summary IS NOT NULL AND summary != '';
