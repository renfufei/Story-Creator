ALTER TABLE prompt_templates ADD COLUMN default_template TEXT;
ALTER TABLE prompt_templates ADD COLUMN default_system_prompt TEXT;

-- Snapshot current content as factory defaults
UPDATE prompt_templates SET default_template = template, default_system_prompt = system_prompt;
