# 全自动创作（AutoRun）步骤分析文档

## 1. 系统架构概览

全自动创作系统采用**策略模式 + 生命周期管理**的架构设计：

```
┌─────────────────────────────────────────────────────────────┐
│                    AutoRunController                         │
│         POST /start, /stop  |  GET /status, /stream         │
└───────────────────────────────┬─────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────┐
│                    AutoRunService                            │
│  - 生命周期管理（启动/停止/状态查询）                          │
│  - 协作式取消（ConcurrentHashMap stopSignals）               │
│  - 并发保护（runningProjects.putIfAbsent 原子检测）          │
│  - 观察管理（AutoRunObservation 实时流）                     │
└───────────────────────────────┬─────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────┐
│                AutoRunStrategyRegistry                       │
│  - 收集所有 AutoRunStrategy Bean                            │
│  - 按名称解析策略（默认 "DEFAULT"）                          │
└───────────────────────────────┬─────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────┐
│              DefaultAutoRunStrategy                          │
│  - 主循环遍历 6 个工作流步骤                                 │
│  - 断点续跑检测                                             │
│  - 分步骤派发到专有子流程                                    │
└───────────────────────────────┬─────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────┐
│                    AutoRunContext                            │
│  - 封装共享基础设施（repos, engine, settings）               │
│  - generateAndSave() — 核心阻塞式生成+保存方法              │
│  - shouldStop() — 停止信号查询                              │
│  - isStepEnabled() / isStepContentComplete()                │
│  - 超时控制 + 进度上报                                      │
└─────────────────────────────────────────────────────────────┘
```

### 核心组件职责

| 组件 | 职责 |
|------|------|
| `AutoRunService` | 生命周期管理：启动虚拟线程、停止信号、状态持久化、观察流管理 |
| `AutoRunStrategy` | 策略接口：`getName()` + `execute(AutoRunContext)` |
| `DefaultAutoRunStrategy` | 默认执行策略：步骤遍历、子流程编排、断点续跑 |
| `AutoRunContext` | 共享上下文：生成调用、停止检测、进度更新、超时管理 |
| `AutoRunObservation` | 实时观察：Reactor Sink 多播、token 缓冲、步骤追踪 |
| `AutoRunStrategyRegistry` | 策略注册表：按名称解析，未找到时回退到 DEFAULT |
| `AutoRunStepConfigEntity` | 步骤配置：每项目每步骤的启用/禁用开关 |

---

## 2. 工作流步骤总览

```
WORLD_BUILDING → CHARACTER_DESIGN → OUTLINE_GENERATION → CHAPTER_WRITING → POLISHING → PROOFREADING
   世界观设定       角色设计            大纲生成            分章节写作        润色修改      校对精修
```

### 状态机

```
项目状态: NOT_STARTED → IN_PROGRESS → COMPLETED / ABANDONED

AutoRun状态:
  IDLE → RUNNING → IDLE       (用户停止)
  IDLE → RUNNING → COMPLETED  (全部完成)
  IDLE → RUNNING → FAILED     (异常/超时)

应用重启时: RUNNING/STOPPING → FAILED (StuckStatusCleaner)
```

---

## 3. 每个步骤的详细执行逻辑

### 3.1 世界观设定（WORLD_BUILDING）

**执行方式**: 单次 AI 生成 + 保存

**调用链**:
```
DefaultAutoRunStrategy.execute()
  → ctx.generateAndSave(WORLD_BUILDING, 0)
    → workflowEngine.generate(projectId, WORLD_BUILDING, 0)
      → WorldBuildingHandler.buildRequest(context)
        → provider.streamText(request)
    → workflowEngine.saveGeneratedContent(...)
```

**AI 参数**:
- 模板: `PromptSubStep` 对应 WORLD_BUILDING 默认模板
- `maxTokens=4096`, `temperature=0.8`
- 变量: `{title, genre, description, stepGuidance}`

**完成判定**: WorldSetting 内容 > 50 字符

**超时倍率**: ×1（基础超时时间）

---

### 3.2 角色设计（CHARACTER_DESIGN）

**执行方式**: 逐卡生成 + 总览生成 + 全部精修（三阶段）

**调用链**:
```
DefaultAutoRunStrategy.execute()
  → runCharacterDesign(ctx)
    │
    ├── 阶段1: ctx.generateAndSave(CHARACTER_DESIGN, 0)
    │     → CharacterGenerationService.generateCharactersByCards(projectId)
    │       ├── 子步骤A: 逐张角色卡生成 [[CHAR:CARD:{n}]]
    │       └── 子步骤B: 角色总览生成 [[CHAR:OVERVIEW]]
    │
    └── 阶段2: runCharacterRefine(ctx)
          → workflowEngine.refineAllCharacters(projectId)
            → CharacterGenerationService.refineAllCharacters(projectId)
              └── 逐张角色卡精修 [[CHAR:REFINE:{n}]] ... [[CHAR:REFINE:DONE]]
```

#### 阶段1：角色卡生成

**子步骤 A — 逐张角色卡** (`PromptSubStep.CHARACTER_CARD`):
- 循环 `1..characterCount`，为每张卡生成独立角色
- 已有内容的卡自动跳过（断点续跑）
- 每张卡完成后：
  - 正则提取结构化字段（姓名/性别/年龄/身份/性格/外貌/背景/动机/能力/关系）
  - AI 生成 ~300 字概要（`contextSummaryService.summarizeCharacterCard()`）
  - 保存为 `status="GENERATED"`
  - 概要加入 `previousSummaries` 供后续卡片差异化参考
- `maxTokens=2048`, `temperature=0.8`

**子步骤 B — 角色总览** (`PromptSubStep.CHARACTER_OVERVIEW`):
- 删除旧总览（sortOrder=0），基于所有角色概要生成全局总览
- AI 生成总览概要（`contextSummaryService.summarizeCharacterOverview()`）
- 保存为 `name="全部角色"`, `sortOrder=0`
- `maxTokens=1024`, `temperature=0.7`

#### 阶段2：角色精修

对每张 `status != "REFINED"` 的角色卡：
1. 设置 `status="REFINING"`
2. 使用 `PromptSubStep.CHARACTER_REFINE` 模板，注入所有角色概要 + 当前卡完整内容
3. 完成后重新提取结构化字段 + 概要字段
4. 设置 `status="REFINED"`
- `maxTokens=2048`, `temperature=0.7`

**完成判定**: 所有角色卡 content > 50 字符 AND 数量 >= characterCount AND 全部 status == "REFINED"

**超时倍率**: 生成阶段 ×8，精修阶段 ×8

---

### 3.3 大纲生成（OUTLINE_GENERATION）

**执行方式**: 分卷弧线 + 逐章大纲 + 大纲精修 + 故事摘要（四阶段流水线）

**调用链**:
```
DefaultAutoRunStrategy.execute()
  → ctx.generateAndSave(OUTLINE_GENERATION, 0)
    → workflowEngine.generate(projectId, OUTLINE_GENERATION, 0)
      → OutlineGenerationService.generateOutlineByChapters(projectId)
        ├── Phase 1: 分卷弧线生成
        ├── Phase 2: 逐章大纲生成
        ├── Phase 2.5: 逐章大纲精修
        └── Phase 3: 故事摘要生成
```

**卷划分**: `totalChapters / chaptersPerVolume`（默认每卷 10 章）

#### Phase 0 — 预创建
- 为所有章节创建 `ChapterOutlineEntity`（status="PENDING"）
- 将任何 GENERATING 状态的记录重置为 PENDING（崩溃恢复）

#### Phase 1 — 分卷弧线生成 (`PromptSubStep.VOLUME_ARC`)
- 哨兵: `[[SECTION:VOLUME:{volNum}:{chStart}:{chEnd}]]`
- 已有 `arcSummary` 的卷自动跳过
- 变量: `{title, genre, description, worldSetting, characters, totalChapters, volumeNumber, chapterStart, chapterEnd, previousArcs, stepGuidance}`
- `maxTokens=1024`, `temperature=0.75`
- 重试: 连接重置/超时时最多 3 次重试（退避 2s-10s）

#### Phase 2 — 逐章大纲生成 (`PromptSubStep.CHAPTER_OUTLINE`)
- 哨兵: `[[SECTION:CHAPTER:{chNum}:{volNum}]]`
- 已完成（COMPLETED/REFINED/REFINING）的章跳过
- 生成前设 `status="GENERATING"`
- 上下文: 所属卷弧线 + 前 5 章大纲
- 阶段提示（phaseHint）: 基于章节/总章比例的 5 级叙事位置提示
- 完成后解析：提取 **标题** 和 **出场角色**，存入独立字段
- `maxTokens=768`, `temperature=0.7`
- 重试: 同 Phase 1

#### Phase 2.5 — 逐章大纲精修 (`PromptSubStep.CHAPTER_OUTLINE_REFINE`)
- 哨兵: `[[SECTION:REFINE:{chNum}:{volNum}]]`
- 已精修（`refined=true`）的章跳过
- 生成前设 `status="REFINING"`
- 上下文: 当前大纲 + 前 3 章 + 后 2 章大纲
- 完成后设 `refined=true`, `status="REFINED"`
- `maxTokens=768`, `temperature=0.65`

#### Phase 3 — 故事摘要 (`PromptSubStep.STORY_SUMMARY`)
- 哨兵: `[[SECTION:SUMMARY]]`
- 汇总所有卷弧线生成全书摘要
- 保存到 `StoryOutlineEntity`
- `maxTokens=1536`, `temperature=0.7`

**完成判定**: StoryOutline 内容 > 50 字符 AND 章节大纲数 >= totalChapters AND 全部 status == "REFINED"

**超时倍率**: ×80（整个 4 阶段流水线共用一个超时窗口）

**进度更新**: 每 2 秒检查当前正在生成/精修的章节，更新进度文本

---

### 3.4 章节写作（CHAPTER_WRITING）

**执行方式**: 逐章生成 + 角色状态快照 + 标题生成

**调用链**:
```
DefaultAutoRunStrategy.execute()
  → runChapterWriting(ctx)
  │   ├── 逐章: ctx.generateAndSave(CHAPTER_WRITING, chapterNum)
  │   └── 逐章后: workflowEngine.generateCharacterStates(projectId, chapterNum)
  │
  → runGenerateTitles(ctx)
      → workflowEngine.generateAndSaveTitles(projectId, shouldStop)
```

#### 章节生成

**断点检测**:
1. 重置 `wordCount=0` 或内容为空的章节状态为 NOT_STARTED
2. 遍历章节列表，找到第一个无内容的章节作为起始点

**逐章循环** (startNum → totalChapters):
1. 检查 `shouldStop()`
2. 调用 `ctx.generateAndSave(CHAPTER_WRITING, chapterNum)`
3. 写作完成后调用 `generateCharacterStates()`（失败仅 warn，不中断）

**AI 参数** (ChapterWritingHandler):
- `maxTokens = max(8192, chapterWordCountMax * 2 + 2000)`
- `temperature=0.85`
- 上下文: 世界观 + 本章出场角色卡 + 本章大纲 + 前一章内容 + 后一章大纲

#### 角色状态快照 (`CharacterStateService`)

每章写完后同步生成：
- 内容摘录: 长章取首 2000 + 末 1000 字符
- 注入: 前一章角色状态 + 本章出场角色名 + 启用的状态维度
- `maxTokens=500`, `temperature=0.3`
- 结果保存到 `chapter.characterStates`
- 失败不影响主流程

#### 标题生成 (`TitleGenerationService`)

全部章节写完后批量执行：
- 逐章生成，已有标题的跳过（断点续跑）
- 取章节前 1000 字符作为预览
- `maxTokens=30`, `temperature=0.5`
- 后处理: 去格式化 → 去引号 → 截断至 12 字符
- 单章失败仅 warn，继续下一章

**完成判定**: 所有章节有非空内容且 wordCount > 0

**超时倍率**: ×10（每章独立计时）

---

### 3.5 润色修改（POLISHING）

**执行方式**: 逐章润色

**调用链**:
```
DefaultAutoRunStrategy.execute()
  → runPolishing(ctx)
    → 循环: ctx.generateAndSave(POLISHING, chapterNum)
      → workflowEngine.generate(projectId, POLISHING, chapterNum)
        → PolishingHandler.buildRequest(context)
          → provider.streamText(request)
      → workflowEngine.saveGeneratedContent(...)
```

**断点检测**: 过滤出 `polishStatus != CONFIRMED` 且有内容的章节

**逐章循环**:
1. 检查 `shouldStop()`
2. 生成前加载 `contentToPolish`（当前章节内容）和 `polishNote`（修改意见）
3. 润色结果直接覆盖章节内容

**AI 参数** (PolishingHandler):
- `maxTokens = max(8192, chapterWordCountMax * 2 + 2000)`
- `temperature=0.6`
- 上下文包含 `【修改意见】` 部分（如果有 polishNote）

**完成判定**: 所有有内容的章节 `polishStatus == CONFIRMED`

**超时倍率**: ×10（每章独立计时）

---

### 3.6 校对精修（PROOFREADING）

**执行方式**: 逐章生成报告（5 子步骤）+ 逐章应用修复（两阶段）

**调用链**:
```
DefaultAutoRunStrategy.execute()
  → runProofreadingAuto(ctx)
    ├── 阶段1: ctx.generateAndSave(PROOFREADING, chapterNum)
    │     → ProofreadingService.runProofreadingSingleChapter(projectId, chNum)
    │       ├── PLOT_SUMMARY 剧情摘要
    │       ├── CHARACTER_CHECK 角色检查
    │       ├── CONSISTENCY 一致性检查
    │       ├── CONTINUITY 连续性检查（第1章跳过）
    │       └── FORESHADOWING 伏笔追踪
    │
    └── 阶段2: ctx.proofreadFixWithProgress(chapterNum, ...)
          → workflowEngine.proofreadFixSingleChapter(projectId, chNum)
            → ProofreadingService.proofreadFixSingleChapter(...)
```

#### 阶段1：校对报告生成（5 个子步骤）

**模型**: 使用 PROOFREADING 步骤模型

每章依次执行 5 个 AI 调用：

| # | 子步骤 | PromptSubStep | 输入 | maxTokens | temperature |
|---|--------|---------------|------|-----------|-------------|
| 1 | 剧情摘要 | PROOFREAD_PLOT_SUMMARY | 章节内容(≤6000字) | 256 | 0.3 |
| 2 | 角色检查 | PROOFREAD_CHARACTER_CHECK | 出场角色 + 内容(≤6000字) | 1024 | 0.2 |
| 3 | 一致性检查 | PROOFREAD_CONSISTENCY | 角色概要 + 前章摘要 + 内容(≤5000字) | 1024 | 0.3 |
| 4 | 连续性检查 | PROOFREAD_CONTINUITY | 前章末200字 + 本章首200字 | 512 | 0.3 |
| 5 | 伏笔追踪 | PROOFREAD_FORESHADOWING | 累积伏笔 + 章号 + 内容(≤5000字) | 1024 | 0.3 |

哨兵令牌: `[[PROOFREAD:CHAPTER:{chNum}:{SUB_STEP}]]`

**第 4 步（CONTINUITY）对第 1 章跳过**。

**累积伏笔**: 跨章节维护的 `List<String>`，每章的伏笔结果追加其中供后续章节参考。

**完成后保存**:
- 创建/更新 `ProofreadingReportEntity`（5 个字段）
- 设置 `chapter.plotSummary`（截断至 500 字符）
- 设置 `chapter.proofreadStatus = GENERATED`
- 更新 `ChapterOutlineEntity.summary`

#### 阶段2：应用修复

**前置条件**: 必须存在该章节的 ProofreadingReport

**逻辑**:
1. 备份原文到 `chapter.contentBeforeFix`
2. 设置 `proofreadFixStatus = GENERATING`
3. 汇总报告中的 `characterIssues` + `consistencyIssues` + `continuityIssues` 为修复依据（跳过 foreshadowing 和 plotSummary）
4. 若无实际问题: 直接设 `proofreadFixStatus = GENERATED`，不调用 AI
5. 否则调用 AI 修复:
   - 模板: `PromptSubStep.PROOFREAD_FIX`
   - 变量: `{reportSummary, originalContent}`
   - `maxTokens=8192`, `temperature=0.3`
6. 修复后更新内容和 wordCount，设 `proofreadFixStatus = GENERATED`
7. 出错时恢复原文，设 `proofreadFixStatus = NOT_STARTED`

哨兵令牌: `[[PROOFREAD_FIX:CHAPTER:{chNum}]]`

**断点检测**:
- 报告阶段: 跳过 `proofreadStatus` 已为 GENERATED/CONFIRMED 的章节
- 修复阶段: 跳过 `proofreadFixStatus` 已为 GENERATED/CONFIRMED 的章节

**完成判定**: 所有有内容的章节 `proofreadFixStatus == GENERATED 或 CONFIRMED`

**超时倍率**: 报告阶段 ×8，修复阶段 ×3

---

## 4. 核心机制

### 4.1 断点续跑逻辑

全自动创作支持在任意中断后从断点恢复。恢复机制分为多层：

#### 步骤级断点

`DefaultAutoRunStrategy.execute()` 入口处执行**回退扫描**:
- 从 `currentStep` 之前的所有步骤开始检查
- 若某个已启用步骤的内容实际不完整，将 `currentStep` 回退到该步骤
- 保证不会跳过未完成的工作

主循环通过 `isStepContentComplete()` 判断是否可跳过:

| 步骤 | 完成条件 |
|------|----------|
| WORLD_BUILDING | WorldSetting 内容 > 50 字符 |
| CHARACTER_DESIGN | 所有角色卡 content > 50 字符 + 数量达标 + 全部 REFINED |
| OUTLINE_GENERATION | StoryOutline > 50 字符 + 章节大纲数达标 + 全部 REFINED |
| CHAPTER_WRITING | 所有章节有非空内容且 wordCount > 0 |
| POLISHING | 所有有内容章节 polishStatus == CONFIRMED |
| PROOFREADING | 所有有内容章节 proofreadFixStatus == GENERATED/CONFIRMED |

#### 章节级断点

- **章节写作**: 找到第一个无内容的章节作为起始点
- **润色**: 过滤 `polishStatus != CONFIRMED` 的章节
- **校对报告**: 检查 `proofreadStatus` 字段
- **校对修复**: 检查 `proofreadFixStatus` 字段

#### 子步骤级断点

- **角色卡**: `completedCardNums` 集合记录已完成的卡号
- **卷弧线**: `completedVolumeNums` 集合
- **章节大纲**: `completedChapterNums` 集合，跳过 COMPLETED/REFINED/REFINING 状态
- **角色精修**: 跳过 `status == "REFINED"` 的角色

---

### 4.2 停止/取消机制（协作式取消）

```
用户点击停止
  → AutoRunController.POST /stop
    → AutoRunService.stopAutoRun(projectId)
      → stopSignals.put(projectId, true)
      → 设置 DB 状态为 STOPPING

虚拟线程中:
  → ctx.shouldStop() 返回 true
  → 当前生成中: dispose 订阅 + resetGeneratingStatus()
  → 返回到 execute()
  → AutoRunService 检测到 shouldStop，调用 markStopped()
    → DB 状态 → IDLE, progress = "已停止"
```

**检查点**:
- 每个步骤开始前
- 每章开始前
- 生成轮询循环中（每 500ms）
- 精修/修复轮询循环中（每 500ms）

**资源清理**: dispose Reactor 订阅 → 重置 GENERATING 状态 → 清理 observation

---

### 4.3 超时控制

基础超时: `GlobalSettingService.getAiTimeoutSeconds()`（默认 300 秒）

| 步骤/子步骤 | 超时倍率 | 默认超时时间 |
|-------------|----------|-------------|
| WORLD_BUILDING | ×1 | 5 分钟 |
| CHARACTER_DESIGN（生成） | ×8 | 40 分钟 |
| CHARACTER_DESIGN（精修） | ×8 | 40 分钟 |
| OUTLINE_GENERATION（全流程） | ×80 | 6.7 小时 |
| CHAPTER_WRITING（每章） | ×10 | 50 分钟 |
| POLISHING（每章） | ×10 | 50 分钟 |
| PROOFREADING 报告（每章） | ×8 | 40 分钟 |
| PROOFREADING 修复（每章） | ×3 | 15 分钟 |
| 标题生成 | 同步 blockLast | 无独立超时 |
| 角色状态 | 同步 blockLast | 无独立超时 |

超时后: dispose 订阅 → 重置状态 → 抛出 RuntimeException → 整个 AutoRun 标记为 FAILED

---

### 4.4 进度报告

进度通过两个通道上报:

#### DB 持久化进度
- `project.autoRunStep` — 当前步骤名
- `project.autoRunChapter` — 当前章节号
- `project.autoRunProgress` — 进度文本（最长 195 字符）

更新时机:
- 步骤开始时: "正在生成: {步骤名}"
- 每 4 秒: "{prefix} (已用时 {n}秒)"
- 大纲生成每 2 秒: "大纲: 正在生成/精修第 {n} 章"
- 步骤完成/跳过时: "{步骤名} 已完成/已跳过"

#### SSE 实时流（AutoRunObservation）
- `Sinks.Many<String>` 热发布者，支持多播
- `StringBuffer tokenBuffer` 用于新客户端重放
- 哨兵令牌 `[[AUTORUN_STEP:{STEP}:{chapter}]]` 标记步骤切换
- `/stream` SSE 端点: step-info + replay-buffer + live tokens + done/error

---

### 4.5 步骤启用/禁用配置

**表**: `auto_run_step_configs`（project_id, step, enabled）

**默认行为**: 无配置记录 = 启用（所有步骤默认开启）

**配置方式**: `PUT /projects/{id}/auto-run/step-config?step=X&enabled=Y`

**跳过逻辑**: 禁用的步骤直接跳过，不执行任何 AI 调用，不更新工作流状态

---

### 4.6 错误处理

| 场景 | 处理方式 |
|------|----------|
| AI 生成超时 | 抛出 RuntimeException → AutoRun 标记 FAILED |
| AI 生成返回空 | 抛出 RuntimeException → AutoRun 标记 FAILED |
| AI Flux 报错 | 抛出 RuntimeException → AutoRun 标记 FAILED |
| 角色状态生成失败 | 仅 log.warn，继续下一章 |
| 标题生成单章失败 | 仅 log.warn，继续下一章 |
| 大纲生成网络错误 | 重试 3 次（退避 2s-10s），仍失败则传播 |
| 校对修复出错 | 恢复原文，设状态 NOT_STARTED，异常向上传播 |
| 线程中断 | dispose 订阅，重新中断线程，返回 |
| 应用重启 | StuckStatusCleaner 重置所有 GENERATING → NOT_STARTED，RUNNING → FAILED |

**错误信息**: 保存到 `project.autoRunError`（截断至 490 字符）

---

## 5. 完整哨兵令牌参考

| 哨兵令牌 | 发射方 | 含义 |
|----------|--------|------|
| `[[AUTORUN_STEP:{STEP}:{chapter}]]` | AutoRunContext | AutoRun 开始一个生成单元 |
| `[[CHAR:CARD:{n}]]` | CharacterGenerationService | 开始第 n 张角色卡生成 |
| `[[CHAR:OVERVIEW]]` | CharacterGenerationService | 开始角色总览生成 |
| `[[CHAR:REFINE:{n}]]` | CharacterGenerationService | 开始第 n 张角色卡精修 |
| `[[CHAR:REFINE:DONE]]` | CharacterGenerationService | 全部角色精修完成 |
| `[[SECTION:VOLUME:{v}:{s}:{e}]]` | OutlineGenerationService | 开始第 v 卷弧线（章节 s-e） |
| `[[SECTION:CHAPTER:{ch}:{v}]]` | OutlineGenerationService | 开始第 ch 章大纲（第 v 卷） |
| `[[SECTION:REFINE:{ch}:{v}]]` | OutlineGenerationService | 开始第 ch 章大纲精修 |
| `[[SECTION:SUMMARY]]` | OutlineGenerationService | 开始故事摘要生成 |
| `[[PROOFREAD:CHAPTER:{ch}:PLOT_SUMMARY]]` | ProofreadingService | 开始第 ch 章剧情摘要 |
| `[[PROOFREAD:CHAPTER:{ch}:CHARACTER_CHECK]]` | ProofreadingService | 开始第 ch 章角色检查 |
| `[[PROOFREAD:CHAPTER:{ch}:CONSISTENCY]]` | ProofreadingService | 开始第 ch 章一致性检查 |
| `[[PROOFREAD:CHAPTER:{ch}:CONTINUITY]]` | ProofreadingService | 开始第 ch 章连续性检查 |
| `[[PROOFREAD:CHAPTER:{ch}:FORESHADOWING]]` | ProofreadingService | 开始第 ch 章伏笔追踪 |
| `[[PROOFREAD_FIX:CHAPTER:{ch}]]` | ProofreadingService | 开始第 ch 章校对修复 |
| `[[BG_STOPPED]]` | BackgroundGenerationService | 后台任务已停止 |
| `[[BG_ERROR:{msg}]]` | BackgroundGenerationService | 后台任务错误 |

---

## 6. 执行流程图（完整）

```
startAutoRun(projectId)
│
├── 检查: 项目未 COMPLETED/ABANDONED
├── 原子检测: runningProjects.putIfAbsent (防重复启动)
├── DB: status → RUNNING
├── 创建 AutoRunObservation
├── 解析策略 (默认 "DEFAULT")
│
└── 虚拟线程 → strategy.execute(ctx)
    │
    ├── [回退扫描] 检查 currentStep 之前的步骤是否真正完成
    │
    └── [主循环] step = currentStep → step.next() → ... → null
        │
        ├── shouldStop? → return
        ├── 步骤禁用? → skip
        ├── 内容完整? → confirmStep + skip
        │
        ├── WORLD_BUILDING
        │   └── generateAndSave(WORLD_BUILDING, 0)
        │
        ├── CHARACTER_DESIGN
        │   ├── generateAndSave(CHARACTER_DESIGN, 0) [如有缺失角色]
        │   │   ├── 逐张角色卡生成 (跳过已有)
        │   │   └── 角色总览生成
        │   └── refineAllCharacters() [如有未精修角色]
        │       └── 逐张角色卡精修
        │
        ├── OUTLINE_GENERATION
        │   └── generateAndSave(OUTLINE_GENERATION, 0)
        │       ├── Phase 1: 分卷弧线 (跳过已有)
        │       ├── Phase 2: 逐章大纲 (跳过已完成)
        │       ├── Phase 2.5: 逐章精修 (跳过已精修)
        │       └── Phase 3: 故事摘要
        │
        ├── CHAPTER_WRITING
        │   ├── runChapterWriting()
        │   │   └── 从断点开始逐章:
        │   │       ├── generateAndSave(CHAPTER_WRITING, chNum)
        │   │       └── generateCharacterStates(chNum) [失败仅 warn]
        │   └── runGenerateTitles()
        │       └── 逐章生成标题 (跳过已有, 失败仅 warn)
        │
        ├── POLISHING
        │   └── runPolishing()
        │       └── 逐章 (polishStatus != CONFIRMED):
        │           └── generateAndSave(POLISHING, chNum)
        │
        └── PROOFREADING
            └── runProofreadingAuto()
                └── 逐章:
                    ├── 阶段1 (proofreadStatus 未完成):
                    │   └── generateAndSave(PROOFREADING, chNum)
                    │       ├── PLOT_SUMMARY
                    │       ├── CHARACTER_CHECK
                    │       ├── CONSISTENCY
                    │       ├── CONTINUITY (ch>1)
                    │       └── FORESHADOWING
                    └── 阶段2 (proofreadFixStatus 未完成):
                        └── proofreadFixWithProgress(chNum)

    [循环结束]
    └── DB: status → COMPLETED, progress = "全自动创作完成！"
```

---

## 7. 注意事项与已知问题

### 已修复的问题
- **TODO #4** (`error[0]` 无内存屏障): 已改用 `AtomicReference<Throwable>`
- **TODO #5** (startAutoRun 竞态): 已改用 `runningProjects.putIfAbsent()` 原子操作

### 仍存在的注意事项

1. **超时机制粗粒度**: OUTLINE_GENERATION 的 ×80 倍率覆盖整个 4 阶段流水线，无法区分单阶段超时
2. **进度 DB 写入频繁**: 每 4 秒写一次 DB（加载项目 → 修改字段 → 保存），高频写入可能造成 H2 负载
3. **标题生成使用同步 `blockLast()`**: 无独立超时保护，依赖全局响应式超时
4. **角色状态生成使用同步 `blockLast()`**: 同上
5. **错误粒度**: 大部分步骤中单个 AI 调用失败会导致整个 AutoRun 标记 FAILED，仅标题和角色状态有独立容错
6. **StuckStatusCleaner 在重启时重置**: 如果应用意外重启，所有进行中的 AutoRun 任务会被标记为 FAILED，需要用户手动重新启动
7. **Observation 30 秒清理**: 任务完成 30 秒后 observation 被移除，之后无法再获取最终状态的 SSE 流
