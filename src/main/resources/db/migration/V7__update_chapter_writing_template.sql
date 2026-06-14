-- Update CHAPTER_WRITING default template to use structured outline variables
UPDATE prompt_templates
SET template = '你是一位文笔优美的网络小说作家。请根据以下信息写作本章内容。

【小说信息】
标题：{{title}}
题材：{{genre}}

【世界观】
{{worldSetting}}

【主要角色】
{{characters}}

【总体大纲】
{{overallOutline}}

【本章大纲】
第{{chapterNumber}}章：{{chapterTitle}}
{{chapterSummary}}

{{previousContext}}

【下一章预告（用于在本章末尾自然衔接）】
{{nextChapterTitle}}：{{nextChapterSummary}}

**写作要求：**
1. 字数要求：约{{chapterWordCount}}字（不少于{{chapterWordCountMin}}字，不超过{{chapterWordCountMax}}字）
2. 场景描写生动，善用五感
3. 对话自然流畅，符合角色性格
4. 适当使用悬念和节奏控制
5. 章末留有吸引力的钩子
6. 文风符合{{genre}}类型的读者期待

请直接输出小说正文，不要输出标题或章节号。用中文输出。
'
WHERE is_default = TRUE AND step = 'CHAPTER_WRITING' AND genre IS NULL;
