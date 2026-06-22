-- Add sub_step column to prompt_templates
ALTER TABLE prompt_templates ADD COLUMN sub_step VARCHAR(50);

-- Insert default templates for all 15 sub-steps
-- CHARACTER_DESIGN: CHARACTER_CARD
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('CHARACTER_DESIGN', 'CHARACTER_CARD', NULL, '角色卡生成',
'你是一位网络小说角色设计师。请为以下小说设计第{{cardNumber}}个主要角色（共{{totalCards}}个）的详细信息卡。

【小说信息】标题：{{title}}，题材：{{genre}}
简介：{{description}}
【世界观摘要】{{worldSetting}}
{{previousContext}}
请严格按以下纯文本格式输出角色信息卡，每个字段独占一行，【字段名】后直接写内容：

【姓名】角色全名
【性别】男/女/其他
【年龄】具体数字或描述性年龄
【身份】角色在故事中的身份/职业
【性格】2-3个关键词加简短描述
【外貌】简洁的外貌特征描述
【背景】角色的前史和来历
【动机】角色在故事中的核心驱动力
【能力】角色的特长或能力
【关系】与其他角色的关键关系

注意：
1. 这是第{{cardNumber}}个角色（共{{totalCards}}个），请确保与其他角色有差异化设计，角色之间有张力。
2. 禁止使用markdown格式（如**加粗**、#标题等），只用纯文本。
3. 每个【字段】必须另起一行，不要将多个字段写在同一行。
4. 用中文输出。',
'你是一位网络小说角色设计师，请生成详细的角色信息卡。',
TRUE,
'你是一位网络小说角色设计师。请为以下小说设计第{{cardNumber}}个主要角色（共{{totalCards}}个）的详细信息卡。

【小说信息】标题：{{title}}，题材：{{genre}}
简介：{{description}}
【世界观摘要】{{worldSetting}}
{{previousContext}}
请严格按以下纯文本格式输出角色信息卡，每个字段独占一行，【字段名】后直接写内容：

【姓名】角色全名
【性别】男/女/其他
【年龄】具体数字或描述性年龄
【身份】角色在故事中的身份/职业
【性格】2-3个关键词加简短描述
【外貌】简洁的外貌特征描述
【背景】角色的前史和来历
【动机】角色在故事中的核心驱动力
【能力】角色的特长或能力
【关系】与其他角色的关键关系

注意：
1. 这是第{{cardNumber}}个角色（共{{totalCards}}个），请确保与其他角色有差异化设计，角色之间有张力。
2. 禁止使用markdown格式（如**加粗**、#标题等），只用纯文本。
3. 每个【字段】必须另起一行，不要将多个字段写在同一行。
4. 用中文输出。',
'你是一位网络小说角色设计师，请生成详细的角色信息卡。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- CHARACTER_DESIGN: CHARACTER_OVERVIEW
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('CHARACTER_DESIGN', 'CHARACTER_OVERVIEW', NULL, '角色总览',
'请根据以下小说信息和已设计的角色，生成一份简洁的角色总览概要（300-400字）。

【小说信息】
标题：{{title}}
题材：{{genre}}
简介：{{description}}

【已设计角色】
{{previousSummaries}}

请输出角色总览，概括每个角色的核心定位、彼此之间的关系网络，以及他们在故事中的作用。
用中文输出，简洁扼要。',
'你是一位网络小说角色设计师，请根据已设计的角色信息生成简洁的角色总览概要。',
TRUE,
'请根据以下小说信息和已设计的角色，生成一份简洁的角色总览概要（300-400字）。

【小说信息】
标题：{{title}}
题材：{{genre}}
简介：{{description}}

【已设计角色】
{{previousSummaries}}

请输出角色总览，概括每个角色的核心定位、彼此之间的关系网络，以及他们在故事中的作用。
用中文输出，简洁扼要。',
'你是一位网络小说角色设计师，请根据已设计的角色信息生成简洁的角色总览概要。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- CHARACTER_DESIGN: CHARACTER_REFINE
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('CHARACTER_DESIGN', 'CHARACTER_REFINE', NULL, '角色精修',
'你是一位资深网络小说角色设计师。请对以下角色卡片进行精修，基于全局世界观和其他角色信息进行一致性校验和细节丰富。

【小说信息】标题：{{title}}，题材：{{genre}}
简介：{{description}}
【世界观摘要】{{worldSetting}}

【全部角色概要】
{{allSummaries}}

【当前角色完整卡片】
{{cardContent}}

精修要求：
1. 校验与世界观一致性（设定、能力体系、时代背景等）
2. 校验与其他角色关系描述互相呼应
3. 丰富细节，使人物更加立体（增加具体事例、细节描写）
4. 保持核心设定不变（姓名、性别、基本身份）

请严格按以下纯文本格式输出精修后的角色信息卡：

【姓名】角色全名
【性别】男/女/其他
【年龄】具体数字或描述性年龄
【身份】角色在故事中的身份/职业
【性格】2-3个关键词加简短描述
【外貌】简洁的外貌特征描述
【背景】角色的前史和来历
【动机】角色在故事中的核心驱动力
【能力】角色的特长或能力
【关系】与其他角色的关键关系
【概要】用不超过300字概括该角色的核心定位、在故事中的作用和关键特征

注意：
1. 禁止使用markdown格式（如**加粗**、#标题等），只用纯文本。
2. 每个【字段】必须另起一行。
3. 用中文输出。',
'你是一位网络小说角色设计师，请精修角色信息卡，确保一致性和细节丰富。',
TRUE,
'你是一位资深网络小说角色设计师。请对以下角色卡片进行精修，基于全局世界观和其他角色信息进行一致性校验和细节丰富。

【小说信息】标题：{{title}}，题材：{{genre}}
简介：{{description}}
【世界观摘要】{{worldSetting}}

【全部角色概要】
{{allSummaries}}

【当前角色完整卡片】
{{cardContent}}

精修要求：
1. 校验与世界观一致性（设定、能力体系、时代背景等）
2. 校验与其他角色关系描述互相呼应
3. 丰富细节，使人物更加立体（增加具体事例、细节描写）
4. 保持核心设定不变（姓名、性别、基本身份）

请严格按以下纯文本格式输出精修后的角色信息卡：

【姓名】角色全名
【性别】男/女/其他
【年龄】具体数字或描述性年龄
【身份】角色在故事中的身份/职业
【性格】2-3个关键词加简短描述
【外貌】简洁的外貌特征描述
【背景】角色的前史和来历
【动机】角色在故事中的核心驱动力
【能力】角色的特长或能力
【关系】与其他角色的关键关系
【概要】用不超过300字概括该角色的核心定位、在故事中的作用和关键特征

注意：
1. 禁止使用markdown格式（如**加粗**、#标题等），只用纯文本。
2. 每个【字段】必须另起一行。
3. 用中文输出。',
'你是一位网络小说角色设计师，请精修角色信息卡，确保一致性和细节丰富。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- OUTLINE_GENERATION: VOLUME_ARC
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('OUTLINE_GENERATION', 'VOLUME_ARC', NULL, '卷弧线生成',
'你是一位经验丰富的网络小说策划。请为以下小说生成第{{volumeNumber}}卷（第{{chapterStart}}-{{chapterEnd}}章，全书共{{totalChapters}}章）的故事弧线。

【小说信息】
标题：{{title}}
题材：{{genre}}
简介：{{description}}

【世界观】
{{worldSetting}}

【主要角色】
{{characters}}
{{previousArcs}}
请生成本卷的故事弧线，包括：
1. 本卷主冲突/核心事件
2. 情绪弧线（起承转合）
3. 关键转折点（1-2个）
4. 与前后卷的衔接（如有前卷，承接前文；暗示后续发展）

简洁概括，300-500字。用中文输出。请使用纯文本格式，不要使用Markdown标记（如星号、井号等）。',
'你是一位经验丰富的网络小说策划，擅长设计故事弧线和节奏控制。',
TRUE,
'你是一位经验丰富的网络小说策划。请为以下小说生成第{{volumeNumber}}卷（第{{chapterStart}}-{{chapterEnd}}章，全书共{{totalChapters}}章）的故事弧线。

【小说信息】
标题：{{title}}
题材：{{genre}}
简介：{{description}}

【世界观】
{{worldSetting}}

【主要角色】
{{characters}}
{{previousArcs}}
请生成本卷的故事弧线，包括：
1. 本卷主冲突/核心事件
2. 情绪弧线（起承转合）
3. 关键转折点（1-2个）
4. 与前后卷的衔接（如有前卷，承接前文；暗示后续发展）

简洁概括，300-500字。用中文输出。请使用纯文本格式，不要使用Markdown标记（如星号、井号等）。',
'你是一位经验丰富的网络小说策划，擅长设计故事弧线和节奏控制。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- OUTLINE_GENERATION: CHAPTER_OUTLINE
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('OUTLINE_GENERATION', 'CHAPTER_OUTLINE', NULL, '章节大纲',
'你是一位网络小说策划。请为以下小说生成第{{chapterNumber}}章（共{{totalChapters}}章，本卷第{{chapterStart}}-{{chapterEnd}}章）的详细大纲。

【小说信息】标题：{{title}}，题材：{{genre}}
【世界观摘要】{{worldSetting}}
【主要角色】{{characters}}
【当前阶段】{{phaseHint}}（第{{chapterNumber}}/{{totalChapters}}章）
{{contextInfo}}
请严格按以下格式输出本章大纲，不要改变格式，不要输出任何分析、解释、策划笔记、前言或总结：

**标题：**（4-10字的章节标题）

**出场角色：**（从主要角色中选择本章涉及的角色姓名，逗号分隔）

（用2-4句话描述本章核心事件、涉及角色、情绪基调和章末悬念，100-200字。注意与前后章节的连贯性和本卷弧线的一致性。）

注意：直接输出上述格式内容即可，不要添加任何其他文字。',
'你是一位网络小说策划，请简洁地生成单章大纲。直接输出大纲内容，禁止输出任何分析、评论或解释说明。',
TRUE,
'你是一位网络小说策划。请为以下小说生成第{{chapterNumber}}章（共{{totalChapters}}章，本卷第{{chapterStart}}-{{chapterEnd}}章）的详细大纲。

【小说信息】标题：{{title}}，题材：{{genre}}
【世界观摘要】{{worldSetting}}
【主要角色】{{characters}}
【当前阶段】{{phaseHint}}（第{{chapterNumber}}/{{totalChapters}}章）
{{contextInfo}}
请严格按以下格式输出本章大纲，不要改变格式，不要输出任何分析、解释、策划笔记、前言或总结：

**标题：**（4-10字的章节标题）

**出场角色：**（从主要角色中选择本章涉及的角色姓名，逗号分隔）

（用2-4句话描述本章核心事件、涉及角色、情绪基调和章末悬念，100-200字。注意与前后章节的连贯性和本卷弧线的一致性。）

注意：直接输出上述格式内容即可，不要添加任何其他文字。',
'你是一位网络小说策划，请简洁地生成单章大纲。直接输出大纲内容，禁止输出任何分析、评论或解释说明。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- OUTLINE_GENERATION: CHAPTER_OUTLINE_REFINE
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('OUTLINE_GENERATION', 'CHAPTER_OUTLINE_REFINE', NULL, '章节大纲精修',
'你正在对已生成的章节大纲进行精修，以消除跨章节的内容重叠和前后矛盾。

【小说信息】标题：{{title}}，题材：{{genre}}
【世界观摘要】{{worldSetting}}
【主要角色】{{characters}}
【当前位置】第{{chapterNumber}}章（共{{totalChapters}}章）
{{contextInfo}}
请精修本章大纲，要求：
1. 保留核心事件和情感基调不变
2. 消除与前后章节的内容重复或矛盾
3. 确保章节间过渡自然、逻辑连贯
4. 如发现本章内容与前后章高度重复，调整本章侧重点使其差异化

请严格按以下格式直接输出精修后的大纲，不要改变格式。禁止输出任何分析过程、精修说明、策划笔记、修改理由或前言后语，只输出最终大纲本身：

**标题：**（4-10字的章节标题，可沿用或微调原标题）

**出场角色：**（从主要角色中选择本章涉及的角色姓名，逗号分隔）

（用2-4句话描述本章核心事件、涉及角色、情绪基调和章末悬念，100-200字。）',
'你是一位网络小说策划，正在对章节大纲进行精修校对。直接输出精修后的大纲内容，禁止输出任何分析过程、修改说明或策划笔记。',
TRUE,
'你正在对已生成的章节大纲进行精修，以消除跨章节的内容重叠和前后矛盾。

【小说信息】标题：{{title}}，题材：{{genre}}
【世界观摘要】{{worldSetting}}
【主要角色】{{characters}}
【当前位置】第{{chapterNumber}}章（共{{totalChapters}}章）
{{contextInfo}}
请精修本章大纲，要求：
1. 保留核心事件和情感基调不变
2. 消除与前后章节的内容重复或矛盾
3. 确保章节间过渡自然、逻辑连贯
4. 如发现本章内容与前后章高度重复，调整本章侧重点使其差异化

请严格按以下格式直接输出精修后的大纲，不要改变格式。禁止输出任何分析过程、精修说明、策划笔记、修改理由或前言后语，只输出最终大纲本身：

**标题：**（4-10字的章节标题，可沿用或微调原标题）

**出场角色：**（从主要角色中选择本章涉及的角色姓名，逗号分隔）

（用2-4句话描述本章核心事件、涉及角色、情绪基调和章末悬念，100-200字。）',
'你是一位网络小说策划，正在对章节大纲进行精修校对。直接输出精修后的大纲内容，禁止输出任何分析过程、修改说明或策划笔记。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- OUTLINE_GENERATION: STORY_SUMMARY
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('OUTLINE_GENERATION', 'STORY_SUMMARY', NULL, '故事总纲',
'你是一位经验丰富的网络小说策划。请根据以下小说信息和各卷弧线，生成一份完整的故事总纲/主线描述。

【小说信息】
标题：{{title}}
题材：{{genre}}
简介：{{description}}
总章数：{{totalChapters}}

【各卷故事弧线】
{{arcsInfo}}

请生成一份500-800字的故事总纲，概括：
1. 整体故事主线和核心冲突
2. 主角的成长/变化轨迹
3. 各阶段关键转折的串联
4. 故事的最终走向和主题升华

用中文输出，确保总纲能完整概括全书脉络。',
'你是一位经验丰富的网络小说策划，请生成完整的故事总纲。',
TRUE,
'你是一位经验丰富的网络小说策划。请根据以下小说信息和各卷弧线，生成一份完整的故事总纲/主线描述。

【小说信息】
标题：{{title}}
题材：{{genre}}
简介：{{description}}
总章数：{{totalChapters}}

【各卷故事弧线】
{{arcsInfo}}

请生成一份500-800字的故事总纲，概括：
1. 整体故事主线和核心冲突
2. 主角的成长/变化轨迹
3. 各阶段关键转折的串联
4. 故事的最终走向和主题升华

用中文输出，确保总纲能完整概括全书脉络。',
'你是一位经验丰富的网络小说策划，请生成完整的故事总纲。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- PROOFREADING: PROOFREAD_PLOT_SUMMARY
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('PROOFREADING', 'PROOFREAD_PLOT_SUMMARY', NULL, '情节摘要',
'请提取以下小说章节的主要情节摘要，50-100字，只输出摘要文字，不要任何前缀或标注。

{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
TRUE,
'请提取以下小说章节的主要情节摘要，50-100字，只输出摘要文字，不要任何前缀或标注。

{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- PROOFREADING: PROOFREAD_CHARACTER_CHECK
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('PROOFREADING', 'PROOFREAD_CHARACTER_CHECK', NULL, '角色名校验',
'以下是小说正文和角色名单。请提取文中出现的所有人物姓名，与角色名单比对。
如发现疑似写错的姓名（音近、形近、别名等），输出JSON数组: [{"found":"错误名","should_be":"正确名","context":"出现位置的前后几个字"}]
如无问题输出空数组 []
只输出JSON，不要任何其他内容。

【角色名单】{{characterNames}}

【正文】
{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
TRUE,
'以下是小说正文和角色名单。请提取文中出现的所有人物姓名，与角色名单比对。
如发现疑似写错的姓名（音近、形近、别名等），输出JSON数组: [{"found":"错误名","should_be":"正确名","context":"出现位置的前后几个字"}]
如无问题输出空数组 []
只输出JSON，不要任何其他内容。

【角色名单】{{characterNames}}

【正文】
{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- PROOFREADING: PROOFREAD_CONSISTENCY
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('PROOFREADING', 'PROOFREAD_CONSISTENCY', NULL, '一致性检查',
'检查本章是否存在与人物设定或前文的矛盾（外貌/能力/关系/地名/时间线）。
输出JSON数组: [{"type":"矛盾类型","description":"具体描述","severity":"high/medium/low"}]
如无问题输出空数组 []
只输出JSON，不要任何其他内容。

【角色设定摘要】
{{characterSummaries}}

【前一章情节摘要】{{previousPlotSummary}}

【本章正文】
{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
TRUE,
'检查本章是否存在与人物设定或前文的矛盾（外貌/能力/关系/地名/时间线）。
输出JSON数组: [{"type":"矛盾类型","description":"具体描述","severity":"high/medium/low"}]
如无问题输出空数组 []
只输出JSON，不要任何其他内容。

【角色设定摘要】
{{characterSummaries}}

【前一章情节摘要】{{previousPlotSummary}}

【本章正文】
{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- PROOFREADING: PROOFREAD_CONTINUITY
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('PROOFREADING', 'PROOFREAD_CONTINUITY', NULL, '衔接检查',
'判断以下两段文字（上一章结尾与本章开头）的衔接是否自然。
如有突兀的跳跃、重复或断裂，输出JSON数组: [{"prev_end":"上章结尾要点","curr_start":"本章开头要点","issue":"具体问题"}]
如衔接良好输出空数组 []
只输出JSON，不要任何其他内容。

【上一章结尾】
{{previousEnd}}

【本章开头】
{{currentStart}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
TRUE,
'判断以下两段文字（上一章结尾与本章开头）的衔接是否自然。
如有突兀的跳跃、重复或断裂，输出JSON数组: [{"prev_end":"上章结尾要点","curr_start":"本章开头要点","issue":"具体问题"}]
如衔接良好输出空数组 []
只输出JSON，不要任何其他内容。

【上一章结尾】
{{previousEnd}}

【本章开头】
{{currentStart}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- PROOFREADING: PROOFREAD_FORESHADOWING
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('PROOFREADING', 'PROOFREAD_FORESHADOWING', NULL, '伏笔检查',
'1. 提取本章新埋下的伏笔/悬念
2. 检查前文伏笔在本章是否已回收

输出JSON数组: [{"type":"planted或resolved","content":"伏笔/悬念内容描述","source_chapter":{{chapterNumber}}}]
- type为"planted"表示本章新埋下的伏笔
- type为"resolved"表示前文伏笔在本章被回收
如无伏笔输出空数组 []
只输出JSON，不要任何其他内容。

【前文已记录的伏笔】
{{accumulatedForeshadowing}}

【本章正文（第{{chapterNumber}}章）】
{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
TRUE,
'1. 提取本章新埋下的伏笔/悬念
2. 检查前文伏笔在本章是否已回收

输出JSON数组: [{"type":"planted或resolved","content":"伏笔/悬念内容描述","source_chapter":{{chapterNumber}}}]
- type为"planted"表示本章新埋下的伏笔
- type为"resolved"表示前文伏笔在本章被回收
如无伏笔输出空数组 []
只输出JSON，不要任何其他内容。

【前文已记录的伏笔】
{{accumulatedForeshadowing}}

【本章正文（第{{chapterNumber}}章）】
{{chapterContent}}',
'你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- PROOFREADING: PROOFREAD_FIX
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('PROOFREADING', 'PROOFREAD_FIX', NULL, '校对修复',
'请根据以下校对报告修改章节正文。只修正报告中指出的问题，不要改动其他内容。

{{reportSummary}}
【原文】
{{originalContent}}',
'你是一位专业小说编辑，根据校对报告修改章节正文。只修改校对报告中指出的问题，保持原文的文风、情节和结构不变。直接输出修改后的完整章节正文，不要添加任何说明或标注。',
TRUE,
'请根据以下校对报告修改章节正文。只修正报告中指出的问题，不要改动其他内容。

{{reportSummary}}
【原文】
{{originalContent}}',
'你是一位专业小说编辑，根据校对报告修改章节正文。只修改校对报告中指出的问题，保持原文的文风、情节和结构不变。直接输出修改后的完整章节正文，不要添加任何说明或标注。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- POLISHING: CHAPTER_TITLE
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('POLISHING', 'CHAPTER_TITLE', NULL, '章节标题',
'请为以下小说章节内容生成一个简短的章节标题，要求：4-12个字，不要带第X章前缀，只输出标题文字，不要标点符号。

{{contentPreview}}',
'你是一位小说编辑，擅长给章节起标题。要求每个标题控制在4-12个字，风格统一，长度尽量一致（建议6-8字）。只输出标题文字，不要任何额外内容。',
TRUE,
'请为以下小说章节内容生成一个简短的章节标题，要求：4-12个字，不要带第X章前缀，只输出标题文字，不要标点符号。

{{contentPreview}}',
'你是一位小说编辑，擅长给章节起标题。要求每个标题控制在4-12个字，风格统一，长度尽量一致（建议6-8字）。只输出标题文字，不要任何额外内容。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- POLISHING: CHARACTER_STATES
INSERT INTO prompt_templates (step, sub_step, genre, name, template, system_prompt, is_default, default_template, default_system_prompt, created_at, updated_at)
VALUES ('POLISHING', 'CHARACTER_STATES', NULL, '角色状态',
'请根据以下章节内容，汇总本章结束时各出场角色的当前状态。
包括：{{dimList}}等。
每个角色一行，格式「角色名：状态描述」，每行不超过50字。只输出状态信息，不要其他内容。
请使用纯文本格式，不要使用Markdown标记。

{{charNames}}
{{prevStates}}
【章节内容】
{{chapterExcerpt}}',
'你是一位小说编辑助手，擅长从章节内容中提取角色状态变化。请简洁准确地汇总。',
TRUE,
'请根据以下章节内容，汇总本章结束时各出场角色的当前状态。
包括：{{dimList}}等。
每个角色一行，格式「角色名：状态描述」，每行不超过50字。只输出状态信息，不要其他内容。
请使用纯文本格式，不要使用Markdown标记。

{{charNames}}
{{prevStates}}
【章节内容】
{{chapterExcerpt}}',
'你是一位小说编辑助手，擅长从章节内容中提取角色状态变化。请简洁准确地汇总。',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
