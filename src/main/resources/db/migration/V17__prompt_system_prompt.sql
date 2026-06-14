ALTER TABLE prompt_templates ADD COLUMN system_prompt TEXT;

-- Backfill existing default templates with system prompts
UPDATE prompt_templates SET system_prompt = '你是一位专业的网络小说世界观架构师，擅长构建宏大而细腻的虚构世界。' WHERE step = 'WORLD_BUILDING' AND is_default = TRUE;
UPDATE prompt_templates SET system_prompt = '你是一位擅长角色塑造的网络小说作家，善于创造有深度、有魅力的角色。' WHERE step = 'CHARACTER_DESIGN' AND is_default = TRUE;
UPDATE prompt_templates SET system_prompt = '你是一位经验丰富的网络小说策划，擅长构建紧凑的故事结构和引人入胜的剧情节奏。' WHERE step = 'OUTLINE_GENERATION' AND is_default = TRUE;
UPDATE prompt_templates SET system_prompt = '你是一位文笔出色的网络小说作家，擅长创作引人入胜的故事内容，文风流畅，描写生动。' WHERE step = 'CHAPTER_WRITING' AND is_default = TRUE;
UPDATE prompt_templates SET system_prompt = '你是一位资深的网络小说编辑，擅长文字润色、情节优化和节奏把控。' WHERE step = 'POLISHING' AND is_default = TRUE;
UPDATE prompt_templates SET system_prompt = '你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。' WHERE step = 'PROOFREADING' AND is_default = TRUE;
