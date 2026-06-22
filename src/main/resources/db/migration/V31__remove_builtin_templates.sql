-- Remove old built-in templates from DB (now served from classpath YAML files)
-- Only deletes rows that have default_template set (i.e. builtin rows).
-- User-created custom templates (default_template IS NULL) are preserved.
DELETE FROM prompt_templates WHERE default_template IS NOT NULL;
