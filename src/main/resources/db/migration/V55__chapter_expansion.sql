ALTER TABLE chapters ADD COLUMN expansion_status VARCHAR(20);
ALTER TABLE chapters ADD COLUMN content_before_expansion TEXT;
ALTER TABLE projects ADD COLUMN expansion_guidance TEXT;
