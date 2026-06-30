# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Compile
mvn compile -q

# Run (background, port 1888, handles existing process)
./start_web.sh

# Run directly (foreground, default port 8080)
mvn spring-boot:run

# Run on specific port
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=1888"

# Package fat jar
mvn package -DskipTests
```

There are no tests in this project currently.

## Database

H2 file-based at `./data/story-creator` (override path with `STORY_DB_PATH` env var). Schema managed by Flyway migrations in `src/main/resources/db/migration/`. H2 console available at `/h2-console` when running.

When adding schema changes, create a new versioned migration file (next is **V28**) — never modify existing migration files.

## Architecture

This is a Spring Boot 3.3 / Java 21 monolith for AI-driven Chinese web novel creation. Key architectural concepts:

### Workflow Pipeline
Linear 6-step creative workflow: `WORLD_BUILDING → CHARACTER_DESIGN → OUTLINE_GENERATION → CHAPTER_WRITING → POLISHING → PROOFREADING`. Each step: AI generates → user reviews/edits → confirms → advances. Defined in `WorkflowStep` enum with ordering.

**PROOFREADING** is a two-phase step: first generates proofreading reports per chapter, then applies fixes. Related chapter fields: `proofreadStatus`, `proofreadFixStatus`, `contentBeforeFix`.

### AI Provider Abstraction (`core/port/ai/`)
- `AiProvider` interface with `generateText()` and `streamText()` (returns `Flux<String>`)
- Three implementations: `ClaudeAiProvider`, `OpenAiProvider`, `OllamaAiProvider`
- All providers build WebClient dynamically per-request from DB config (not at startup)
- `AiRequest` carries `baseUrl`/`apiKey` so providers use per-request connection info

### 3-Level Model Resolution (`ai/router/AiProviderRouter`)
Model selection cascades: step-level override → project default → global default → first active config with API key. Returns `ResolvedModel` record with config's baseUrl/apiKey.

### Workflow Engine (`workflow/engine/WorkflowEngine`)
Central orchestrator. Builds `WorkflowContext` from all project data, delegates to step-specific `WorkflowStepHandler` implementations, handles content persistence to both `workflow_states` and domain-specific tables (chapters, world settings, etc.).

Key public methods beyond basic generate/save:
- `generateCharacterStates(projectId, chapterNumber)` — per-chapter character state snapshots
- `refineAllCharacters(projectId)` — refines all unrefined character cards
- `proofreadFixSingleChapterSync(projectId, chapterNumber)` — synchronous proofreading fix
- `generateAndSaveTitles(projectId, shouldStop)` — title generation with cooperative cancellation

### Background Generation (`workflow/background/BackgroundGenerationService`)
Decouples generation from SSE connection. Starts a virtual-thread task that streams tokens into a `Sinks.Many<String>` buffer. Clients can reconnect and replay accumulated buffer. Tasks auto-clean 30s after completion. Controller at `/projects/{projectId}/bg-gen` with endpoints: `POST /start`, `POST /stop`, `GET /status`, `GET /stream` (SSE).

Sentinel tokens in stream: `[[CHAR:...]]`, `[[SECTION:...]]`, `[[PROOFREAD:...]]`, `[[BG_STOPPED]]`, `[[BG_ERROR:...]]`.

### AutoRun (`workflow/autorun/AutoRunService`)
Full-auto creation mode. Virtual thread executes entire workflow pipeline. Features:
- Per-step enable/disable config via `auto_run_step_configs` table
- Cooperative cancellation via `ConcurrentHashMap` stop signals
- After CHARACTER_DESIGN: auto-refines all characters
- After each chapter write: generates character states, then titles
- Blocks on projects with COMPLETED or ABANDONED status
- Timeout multipliers per step type (outline ×80, character/chapter ×8–10, proofreading ×30)
- Breakpoint resume: step-level via `currentStep`, chapter-level via finding chapters without content, polish via `polishStatus`

### Context Summarization (`workflow/engine/ContextSummaryService`)
AI-powered compression of world settings (~500 chars) and character cards (~300 chars) before injecting into prompts. Falls back to null on failure (caller uses truncation).

### Streaming (SSE)
`WorkflowController.generate()` returns `SseEmitter` with 5-min timeout. Uses virtual thread executor. Events: `token` (incremental text), `done` (complete), `error`. On disconnect/timeout, `resetGeneratingStatus()` prevents stuck states. `StuckStatusCleaner` resets any orphaned GENERATING statuses on startup.

### Prompt Templates (`ai/prompt/PromptTemplateRegistry`)
Templates stored in DB with `{{placeholder}}` variables. `WorkflowContext.toTemplateVariables()` produces the variable map. Templates are genre-aware with fallback to generic. Templates support a `systemPrompt` field (V17).

### Frontend
Thymeleaf + Alpine.js + Bootstrap 5. Key pages:
- `workflow.html` — main workflow interface with Alpine.js reactive state, AJAX chapter list, EventSource SSE
- `reader.html` — reading view of completed novel
- `import.html` — project import interface

Chapter list data loaded via `/projects/{id}/chapters/list` JSON endpoint.

### Export & Import (`export/`)
- **Export**: Markdown, TXT, EPUB, and PDF formats via `/projects/{id}/export?format=X`. PDF uses OpenPDF with STSong-Light CJK font.
- **Import**: JSON project import via `/import` (GET page, POST upload). Supports overwrite mode. Imports all project data including proofreading reports and step guidances.

### AI Usage Tracking (`workflow/engine/AiUsageTracker`)
Tracks cumulative AI call duration per project+model combination in `ai_usage_stats` table. Thread-safe, failures swallowed.

## Key Patterns

- Entities use `@PrePersist`/`@PreUpdate` for timestamps
- Controllers at `/projects/{projectId}/...` use `@RequestMapping` prefix
- Chapter operations: generation auto-saves on SSE completion (server-side), no separate save step needed
- Polish notes: stored per-chapter, included in prompt as `【修改意见】` when present
- `polishStatus` tracks per-chapter polish state independently from chapter `status`
- Character lifecycle: GENERATED → REFINED (tracked by `status` field on `CharacterEntity`)
- Project lifecycle: NOT_STARTED → IN_PROGRESS → COMPLETED/ABANDONED (tracked by `ProjectStatus` enum)
- `GlobalSettingService` provides key-value store for app-wide settings (e.g., `ai_timeout_seconds`, default 300s)

## Environment Variables

- `CLAUDE_API_KEY` — Claude API key
- `OPENAI_API_KEY` — OpenAI API key (or compatible proxy)
- `STORY_DB_PATH` — Override H2 database directory (default: `./data`)

AI model configs are primarily managed through the Settings UI (`/settings`) and stored in the `ai_model_configs` table. The `extraParams` field supports free-form provider parameters. The env vars serve as fallback.

## TODO

### Phase 1 — 关键 Bug（影响功能正确性）

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| 1 | `ClaudeAiProvider.generateText()` 始终设置 `stream: true` | `ClaudeAiProvider.java` | 非流式调用收到 SSE 流，JSON 解析失败返回空字符串 |
| 2 | `resolveModelForProject()` 硬编码 POLISHING 步骤 | `WorkflowEngine.java` | 标题生成等调用始终使用 POLISHING 模型配置，忽略其他步骤覆盖 |
| 3 | `BackgroundGenerationService.contentBuffer` 非线程安全 | `BackgroundGenerationService.java` | `StringBuilder` 被多线程同时读写，可能乱码或异常 |
| 4 | `AutoRunService.generateAndSave` 的 `error[0]` 无内存屏障 | `AutoRunService.java` | Reactor 线程写入、虚拟线程读取，可能读不到错误状态 |
| 5 | `startAutoRun` check-then-act 竞态条件 | `AutoRunService.java` | 双击可导致同一项目启动两个自动运行任务 |

### Phase 2 — 高优先级（用户体验与稳定性）

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| 6 | 无全局异常处理器 (`@ControllerAdvice`) | 缺失 | `orElseThrow` 产生 500 + 堆栈暴露给浏览器 |
| 7 | `OpenAiProvider` 注入 vLLM 专属参数 `chat_template_kwargs` | `OpenAiProvider.java` | 标准 OpenAI API 不认识，严格代理会拒绝 |
| 8 | Ollama/OpenAI `streamText()` 无 HTTP 错误状态处理 | `OllamaAiProvider.java` | 4xx/5xx 被静默吞掉 |
| 9 | `generateAndSaveTitles` 无逐章错误处理 | `WorkflowEngine.java` | 单章失败中断所有剩余章节 |
| 10 | 前端 `setupCharacterStreamEvents` 缺少 `stopped` 事件 | `workflow.html` | 停止角色后台生成后 `generating` 永远为 true |
| 11 | 前端 `advanceStep()` / `confirmStep()` 无 `.catch()` | `workflow.html` | 网络失败时无反馈 |
| 12 | `deleteCharacter` 缺少项目归属校验 | `WorkflowController.java` | 可跨项目删除角色 |

### Phase 3 — 中优先级

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| 13 | `AiUsageTracker` 读-改-写竞态 | `AiUsageTracker.java` | 应用 SQL `UPDATE SET x = x + ?` 或乐观锁 |
| 14 | `BackgroundGenerationService.startGeneration` TOCTOU | `BackgroundGenerationService.java` | 并发启动覆盖已有任务，泄漏 sink/disposable |
| 15 | SSE 错误事件暴露原始异常信息 | `WorkflowController.java` | 应过滤内部错误细节 |
| 16 | `global-replace` 不更新派生字段 | `WorkflowController.java` | plotSummary、characterStates、校对报告中仍保留旧内容 |
| 17 | `generate()` 无步骤顺序校验 | `WorkflowEngine.java` | 可对已完成步骤重新生成覆盖内容 |
| 18 | `proofreadFixChapter()` 前端竞态 | `workflow.html` | SSE 流先于 draft 加载，左面板短暂空白 |
| 19 | `pollAutoRunStatus` 按中文标签匹配步骤 | `workflow.html` | 脆弱，应按 enum name 匹配 |
| 20 | `ContextSummaryService` 无缓存 | `ContextSummaryService.java` | 相同内容重复调用 AI 摘要 |
| 21 | 标题 SSE 端点 120s 超时对大型小说不够 | `WorkflowController.java` | 100+ 章节会超时中断 |
| 22 | `task.disposable` 可能为 null 时被调用 | `BackgroundGenerationService.java` | subscribe 异常时 disposable 未赋值 |

### Phase 4 — 低优先级 / 代码清理

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| 23 | 死代码: `saveCharacters()`, `WorkflowStep.previous()` | 多处 | 未被调用，应清理 |
| 24 | 死代码: `startOutlineSSE`, `startProofreadSSE`, `loadChapterListAsync`, `confirmForm` | `workflow.html` | 前端未调用的函数/元素 |
| 25 | htmx 加载但未使用 | `layout.html` | 每页多加载 14KB 无用 JS |
| 26 | 提示词模板系统大部分被绕过 | `WorkflowEngine.java` | 角色/大纲/写作/校对硬编码 prompt，模板编辑器无效 |
| 27 | 设置页面无法清除已有 API Key | `SettingsController.java` | 非空判断导致无法置空 |
| 28 | 未使用的 DB 表 `workflow_model_assignments` | V1 migration | 已被 `auto_run_step_configs` 取代 |
| 29 | CDN 依赖无 SRI hash | `layout.html` | 安全最佳实践缺失 |
| 30 | 无 dev/prod 配置分离 | `application.yml` | H2 console、关闭模板缓存对生产不安全 |
| 31 | `refineAllCharacters` SSE 连接未存入 `currentEventSource` | `workflow.html` | 无法从 UI 取消精修 |
| 32 | 多处 fetch 未检查 `r.ok` | `workflow.html` | 服务器返回 HTML 错误页时 JSON 解析异常 |

### Phase 5 — 新功能需求

| # | 功能 | 说明 |
|---|------|------|
| 33 | 项目蒸馏（备选库） | 项目列表增加【蒸馏】按钮，可选择已有项目，将创意、角色、技能等信息加入【备选库】，方便后期其他项目参考创意 |
| 34 | 通用创作指导复用 | 每个部分的创作指导可加入通用创作指导（根据步骤和说明信息），其他项目可以引入/拷贝 |
