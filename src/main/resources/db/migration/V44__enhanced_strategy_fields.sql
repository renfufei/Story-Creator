-- Enhanced strategy: behavior boundaries for characters
ALTER TABLE characters ADD COLUMN behavior_boundaries TEXT;

-- Enhanced strategy: event plan for chapter outlines
ALTER TABLE chapter_outlines ADD COLUMN event_plan TEXT;

-- Enhanced strategy: 7-step writing cycle intermediate results
ALTER TABLE chapters ADD COLUMN writing_briefing TEXT;
ALTER TABLE chapters ADD COLUMN writing_reasoning TEXT;
ALTER TABLE chapters ADD COLUMN instant_review TEXT;
ALTER TABLE chapters ADD COLUMN storyline_snapshot TEXT;
ALTER TABLE chapters ADD COLUMN deep_review TEXT;
ALTER TABLE chapters ADD COLUMN writing_cycle_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED';
