-- file_path nullable (allow prompt-only records without image yet)
ALTER TABLE character_images ALTER COLUMN file_path VARCHAR(500) NULL;

-- Separate image_config_id and text_config_id (split from model_config_id)
ALTER TABLE character_images ADD COLUMN image_config_id BIGINT;
ALTER TABLE character_images ADD COLUMN text_config_id BIGINT;
UPDATE character_images SET image_config_id = model_config_id;

-- Characters: universal image prompt template
ALTER TABLE characters ADD COLUMN image_prompt_template VARCHAR(2000);
