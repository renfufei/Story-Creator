# CLAUDE.md

## Build & Run

```bash
./start_web.sh               # 打包并后台启动 (port 1888)
./run_unit_tests.sh          # 运行全部测试
```

## Database

H2 file-based at `./data/story-creator` (override with `STORY_DB_PATH` env var). Flyway migrations in `src/main/resources/db/migration/`. Next migration: **V49**. Never modify existing migration files.

## Architecture

Spring Boot 3.3 / Java 21 monolith for AI-driven Chinese web novel creation.

### Core Concepts

- **Workflow Pipeline**: `WORLD_BUILDING → CHARACTER_DESIGN → OUTLINE_GENERATION → CHAPTER_WRITING → POLISHING → PROOFREADING` (defined in `WorkflowStep` enum)
- **AI Providers** (`core/port/ai/`): `AiProvider` interface → `ClaudeAiProvider`, `OpenAiProvider`, `OllamaAiProvider`. WebClient built per-request from DB config. `AiRequest` carries `baseUrl`/`apiKey`.
- **Model Resolution** (`ai/router/AiProviderRouter`): step-level override → project default → global default → first active config
- **WorkflowEngine** (`workflow/engine/`): Facade (~288 lines) + 7 service classes. Builds `WorkflowContext`, delegates to `WorkflowStepHandler` implementations.
- **Background Generation** (`workflow/background/`): Virtual-thread tasks stream into `Sinks.Many<String>` buffer. Sentinel tokens: `[[CHAR:...]]`, `[[SECTION:...]]`, `[[PROOFREAD:...]]`, `[[BG_STOPPED]]`, `[[BG_ERROR:...]]`.
- **AutoRun** (`workflow/autorun/`): Strategy pattern — `AutoRunService` (lifecycle) + `AutoRunStrategy` interface. Strategies: `DefaultAutoRunStrategy`, `EnhancedAutoRunStrategy`. Project stores strategy name in `ProjectEntity.autoRunStrategy`.
- **Prompt Templates** (`ai/prompt/PromptTemplateRegistry`): DB custom (isDefault=true) → builtin YAML (genre-specific → generic). `{{placeholder}}` variables. `PromptSubStep` enum maps 15 sub-steps.
- **SSE Streaming**: `SseEmitter` with 5-min timeout. Events: `token`, `done`, `error`. `StuckStatusCleaner` resets orphaned GENERATING statuses on startup.

### Supporting Features

- **TTS**: `TtsProvider` interface + `OpenAiTtsProvider`. Replacement templates in `src/main/resources/tts-templates/`. Controller at `/projects/{id}/tts/`.
- **Character Images**: `ImageProvider` interface → `OpenAiImageProvider`, `SdWebUiImageProvider`. Images at `data/images/{projectId}/{characterId}/`. Controller at `/projects/{id}/characters/{charId}/images/`.
- **Material Library**: Project-level categorized materials with AI distillation. Controller at `/projects/{id}/materials/`.
- **Export/Import**: Markdown, TXT, EPUB, PDF export. JSON project import with overwrite mode.
- **Context Summarization** (`ContextSummaryService`): AI compression with SHA-256 cache.
- **AI Usage Tracking** (`AiUsageTracker`): Per project+model duration stats.

### Frontend

Thymeleaf + Alpine.js + Bootstrap 5. Key pages: `workflow.html`, `reader.html`, `import.html`.

## Key Patterns

- Entities use `@PrePersist`/`@PreUpdate` for timestamps
- Controllers at `/projects/{projectId}/...` with `@RequestMapping` prefix
- Chapter generation auto-saves on SSE completion (server-side)
- Character lifecycle: GENERATED → REFINED
- Project lifecycle: NOT_STARTED → IN_PROGRESS → COMPLETED/ABANDONED
- `GlobalSettingService`: key-value store for app settings (e.g., `ai_timeout_seconds`, default 300s)
- `ModelType` enum: TEXT, TTS, IMAGE

## Environment Variables

- `CLAUDE_API_KEY` — Claude API key
- `OPENAI_API_KEY` — OpenAI API key (or compatible proxy)
- `STORY_DB_PATH` — Override H2 database directory (default: `./data`)

AI model configs managed via Settings UI (`/settings`) → `ai_model_configs` table. `extraParams` field for provider-specific parameters.
