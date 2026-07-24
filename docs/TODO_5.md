# TODO

## 待办事项

---

### 5. 导入修复模式（TXT Import & Reverse Engineering）

#### 问题

当前导入功能仅支持 JSON 格式的项目数据包，要求数据结构完整（世界观、角色、大纲、章节等字段齐全）。这意味着：

1. **无法导入已有作品**：用户如果有已完成或半完成的 TXT 格式小说，无法导入系统进行后续管理和优化
2. **缺少反推能力**：已有文本中蕴含的世界观、角色信息、剧情结构等无法被系统识别和结构化
3. **断点续写困难**：用户无法将外部创作的内容导入后，利用系统的 AI 能力继续创作

#### 核心理念

> **从成品反推半成品，再用系统能力补全和增强。** 将一份纯文本小说导入后，通过 AI 逆向分析出世界观、角色、大纲等结构化信息，使其成为系统中的完整项目，后续可利用所有现有功能（润色、扩写、续写等）。

#### 设计方案

##### 5.1 导入格式支持

支持 `.txt` 文件导入，自动识别并切割章节结构：

**章节切割规则**（按优先级）：
1. 正则匹配常见章节标题格式：`第X章`、`第X节`、`Chapter X`、`第一章`、`第1章` 等
2. 匹配分隔线模式：连续的 `===`、`---`、`***` 等
3. 匹配自定义标题格式：以数字或序号开头的独立短行（≤30字且前后有空行）
4. 用户可在导入时手动指定切割正则表达式（高级选项）

**切割结果**：
- 书名/标题：从文件名或首行提取
- 章节列表：每章包含标题 + 正文内容

##### 5.2 反推补全流程

导入切割后，系统通过 AI 逐步反推出结构化信息，组合复用现有步骤：

```
TXT 导入
  ├── Phase 0: 章节切割（纯规则，非 AI）
  │     └── 输出：章节标题 + 内容列表
  ├── Phase 1: 世界观反推（AI）
  │     ├── 输入：前 N 章内容 + 全部章节标题列表
  │     └── 输出：生成世界观设定 → 保存到 world_settings
  ├── Phase 2: 角色反推（AI）
  │     ├── 输入：全文（或采样章节） + 反推的世界观
  │     └── 输出：识别主要角色 → 生成角色卡 → 保存到 characters
  ├── Phase 3: 大纲反推（AI）
  │     ├── 输入：全部章节标题 + 各章内容摘要 + 角色列表
  │     └── 输出：分卷大纲 + 章节大纲 → 保存到 volume_outlines / chapter_outlines
  └── Phase 4: 章节内容保存
        └── 将切割后的原文直接存入 chapters.content
```

##### 5.3 反推专用提示词模板

新增 `PromptSubStep` 用于反推：

| SubStep | 说明 | 关键变量 |
|---|---|---|
| `REVERSE_WORLD_BUILDING` | 从正文反推世界观 | `{{sampleChapters}}`, `{{allChapterTitles}}`, `{{genre}}` |
| `REVERSE_CHARACTER_EXTRACTION` | 从正文提取角色信息 | `{{sampleChapters}}`, `{{worldSetting}}`, `{{allChapterTitles}}` |
| `REVERSE_OUTLINE_GENERATION` | 从正文反推大纲结构 | `{{allChapterTitles}}`, `{{chapterSummaries}}`, `{{characters}}`, `{{worldSetting}}` |

##### 5.4 导入配置选项

用户在导入时可配置：

| 配置项 | 说明 | 默认值 |
|---|---|---|
| 文件编码 | UTF-8 / GBK / GB2312 / 自动检测 | 自动检测 |
| 章节切割正则 | 自定义章节标题匹配模式 | 内置规则集 |
| 反推深度 | 需要反推哪些内容（世界观/角色/大纲，可多选） | 全部 |
| 采样策略 | 反推时读取多少章内容（全部/前N章/均匀采样） | 前10章 + 均匀采样 |
| 目标类型 | 小说类型/流派（辅助 AI 反推） | 用户选择 |

##### 5.5 导入状态与断点续跑

导入修复是一个多步骤的耗时过程，需要支持状态跟踪和断点续跑：

- `projects.import_mode`：新增字段，标记 `REVERSE_ENGINEERING`（导入修复中）
- `projects.import_phase`：当前进行到的阶段（`SPLITTING` / `WORLD` / `CHARACTER` / `OUTLINE` / `DONE`）
- 每完成一个 Phase 自动保存，中断后可从当前 Phase 继续

##### 5.6 用户交互流程

```
上传 TXT 文件
  → 预览切割结果（显示识别到的章节数、各章标题）
  → 用户确认/调整切割（可手动合并或拆分章节）
  → 选择反推配置
  → 开始反推（显示进度：世界观反推中... → 角色识别中... → 大纲生成中...）
  → 反推完成，进入项目编辑页
  → 用户审阅/修改反推结果
  → 可正常使用系统所有功能（续写、润色、扩写等）
```

##### 5.7 与现有步骤的组合

反推完成后，项目状态等同于正常创作流程已完成对应步骤的项目：

- 世界观反推完成 → 等同于 `WORLD_BUILDING` 步骤已完成
- 角色反推完成 → 等同于 `CHARACTER_DESIGN` 步骤已完成
- 大纲反推完成 → 等同于 `OUTLINE_GENERATION` 步骤已完成
- 章节内容已导入 → 等同于 `CHAPTER_WRITING` 步骤已完成

用户可以从任意步骤重新执行（如对反推的世界观不满意，可重新生成），也可以直接利用后续步骤（如对导入的章节执行润色、扩写）。

##### 5.8 数据模型变更

```sql
ALTER TABLE projects ADD COLUMN import_mode VARCHAR(30);       -- NULL / REVERSE_ENGINEERING
ALTER TABLE projects ADD COLUMN import_phase VARCHAR(20);      -- SPLITTING / WORLD / CHARACTER / OUTLINE / DONE
ALTER TABLE projects ADD COLUMN import_config TEXT;            -- JSON: 存储导入配置（编码、正则、采样策略等）
```

##### 5.9 兼容性考虑

- 现有项目不受影响，`import_mode` 默认为 NULL
- 导入修复模式的项目与正常创建的项目共享同一套数据结构，反推完成后行为完全一致
- 导入的原始 TXT 文件可选保留备份到 `data/imports/{projectId}/original.txt`
- 反推过程中用户可随时中止，已完成的 Phase 结果保留
- 未来可扩展支持更多格式（EPUB、DOCX 等），只需增加 Phase 0 的解析器
