-- Migrate existing tts_text_replacements into a user template
-- Only runs if there are existing rules
INSERT INTO tts_replacement_templates (name, description, enabled, sort_order)
SELECT '已有替换规则（迁移）', '从旧版 TTS 文字替换规则迁移而来', TRUE, 0
FROM tts_text_replacements
LIMIT 1;

INSERT INTO tts_replacement_rules (template_id, pattern, replacement, is_regex, description, sort_order)
SELECT
    (SELECT id FROM tts_replacement_templates WHERE name = '已有替换规则（迁移）'),
    source_text,
    target_text,
    FALSE,
    NULL,
    sort_order
FROM tts_text_replacements
WHERE (SELECT id FROM tts_replacement_templates WHERE name = '已有替换规则（迁移）') IS NOT NULL;
