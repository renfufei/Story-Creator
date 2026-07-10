# 事件计划 (Event Plan)

## 概述

事件计划是 ENHANCED 自动运行策略中的一个子步骤功能，为每个章节生成详细的事件推演计划，在后续章节写作时作为剧情推演的上下文输入。

## 数据库存储

- **表**: `chapter_outlines`
- **字段**: `event_plan TEXT`
- **迁移**: `V44__enhanced_strategy_fields.sql`
- **实体**: `ChapterOutlineEntity.eventPlan`

每个章节大纲记录对应一条事件计划。

## 生成阶段

**阶段**: `OUTLINE_GENERATION`（大纲生成）
**策略**: 仅 ENHANCED 自动运行策略
**子步骤**: `EVENT_PLAN`（需在前端启用）

### 生成流程

1. `EnhancedAutoRunStrategy.runOutlineGeneration()` 完成标准大纲生成后
2. 检查子步骤 `EVENT_PLAN` 是否启用
3. 遍历所有章节大纲，对 `eventPlan` 为空的章节：
   - 构建变量 Map
   - 调用 `EnhancedSubStepExecutor.generateEventPlan()`
   - 将结果保存到 `chapter_outlines.event_plan`

### 生成模板

- **文件**: `src/main/resources/prompts/OUTLINE_GENERATION/CHAPTER_EVENT_PLAN.yaml`
- **PromptSubStep**: `CHAPTER_EVENT_PLAN`
- **输入变量**: `title`, `genre`, `worldSetting`, `characters`, `writingRules`, `styleFingerprint`, `chapterNumber`, `chapterSummary`, `stepGuidance`

## 使用阶段

**阶段**: `CHAPTER_WRITING`（章节写作）
**步骤**: Step 2 — 剧情推演 (Plot Reasoning)

### 使用流程

1. `EnhancedChapterWritingService.writeChapterEnhanced()` 加载当前章节的 `eventPlan`
2. 作为 `{{eventPlan}}` 变量传入剧情推演模板
3. AI 结合事件计划进行剧情推演，结果保存到 `chapters.writing_reasoning`

### 消费模板

- **文件**: `src/main/resources/prompts/CHAPTER_WRITING/CHAPTER_PLOT_REASONING.yaml`
- **PromptSubStep**: `CHAPTER_PLOT_REASONING`
- **使用变量**: `title`, `genre`, `chapterNumber`, `chapterSummary`, `eventPlan`, `writingBriefing`, `characterCards`, `stepGuidance`

## 数据流图

```
OUTLINE_GENERATION (仅 ENHANCED 策略)
  └─ runOutlineGeneration()
       └─ 子步骤 EVENT_PLAN (如启用):
            对每个章节:
              → CHAPTER_EVENT_PLAN.yaml 模板生成
              → 结果保存到 chapter_outlines.event_plan
                    ↓
CHAPTER_WRITING (ENHANCED 7步写作)
  └─ Step 2: 剧情推演 (Plot Reasoning)
       ← 从 chapter_outlines.event_plan 读取
       → CHAPTER_PLOT_REASONING.yaml 模板消费 {{eventPlan}}
       → 结果保存到 chapters.writing_reasoning
```

## 相关文件

| 文件 | 作用 |
|------|------|
| `V44__enhanced_strategy_fields.sql` | 添加 `event_plan` 列 |
| `ChapterOutlineEntity.java` | 实体字段定义 |
| `EnhancedAutoRunStrategy.java` | 生成逻辑入口 |
| `EnhancedSubStepExecutor.java` | 调用 AI 生成事件计划 |
| `EnhancedChapterWritingService.java` | 读取并传入写作流程 |
| `OUTLINE_GENERATION/CHAPTER_EVENT_PLAN.yaml` | 生成模板 |
| `CHAPTER_WRITING/CHAPTER_PLOT_REASONING.yaml` | 消费模板 |
| `InspectController.java` | 检查/查看 eventPlan 接口 |
| `PromptExploreService.java` | 提示词探索 UI 变量加载 |
| `static/js/workflow/autorun.js` | 前端子步骤配置 |
