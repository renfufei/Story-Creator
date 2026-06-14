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

H2 file-based at `./data/story-creator`. Schema managed by Flyway migrations in `src/main/resources/db/migration/`. H2 console available at `/h2-console` when running.

When adding schema changes, create a new versioned migration file (V6, V7, etc.) — never modify existing migration files.

## Architecture

This is a Spring Boot 3.3 / Java 21 monolith for AI-driven Chinese web novel creation. Key architectural concepts:

### Workflow Pipeline
Linear 5-step creative workflow: `WORLD_BUILDING → CHARACTER_DESIGN → OUTLINE_GENERATION → CHAPTER_WRITING → POLISHING`. Each step: AI generates → user reviews/edits → confirms → advances. Defined in `WorkflowStep` enum with ordering.

### AI Provider Abstraction (`core/port/ai/`)
- `AiProvider` interface with `generateText()` and `streamText()` (returns `Flux<String>`)
- Three implementations: `ClaudeAiProvider`, `OpenAiProvider`, `OllamaAiProvider`
- All providers build WebClient dynamically per-request from DB config (not at startup)

### 3-Level Model Resolution (`ai/router/AiProviderRouter`)
Model selection cascades: step-level override → project default → global default → first active config with API key.

### Workflow Engine (`workflow/engine/WorkflowEngine`)
Central orchestrator. Builds `WorkflowContext` from all project data, delegates to step-specific `WorkflowStepHandler` implementations, handles content persistence to both `workflow_states` and domain-specific tables (chapters, world settings, etc.).

### Streaming (SSE)
`WorkflowController.generate()` returns `SseEmitter` with 5-min timeout. Uses virtual thread executor. Events: `token` (incremental text), `done` (complete), `error`. On disconnect/timeout, `resetGeneratingStatus()` prevents stuck states. `StuckStatusCleaner` resets any orphaned GENERATING statuses on startup.

### Prompt Templates (`ai/prompt/PromptTemplateRegistry`)
Templates stored in DB with `{{placeholder}}` variables. `WorkflowContext.toTemplateVariables()` produces the variable map. Templates are genre-aware with fallback to generic.

### Frontend
Thymeleaf + Alpine.js + Bootstrap 5. The workflow page (`workflow.html`) is the most complex — uses Alpine.js for reactive state, AJAX for chapter list refresh, and EventSource for SSE streaming. Chapter list data loaded via `/projects/{id}/chapters/list` JSON endpoint.

### Export (`export/ExportService`)
Supports Markdown, TXT, and EPUB formats via `/projects/{id}/export?format=X`.

## Key Patterns

- Entities use `@PrePersist`/`@PreUpdate` for timestamps
- Controllers at `/projects/{projectId}/...` use `@RequestMapping` prefix
- Chapter operations: generation auto-saves on SSE completion (server-side), no separate save step needed
- Polish notes: stored per-chapter, included in prompt as `【修改意见】` when present
- `polishStatus` tracks per-chapter polish state independently from chapter `status`

## Environment Variables

- `CLAUDE_API_KEY` — Claude API key
- `OPENAI_API_KEY` — OpenAI API key (or compatible proxy)

AI model configs are primarily managed through the Settings UI (`/settings`) and stored in the `ai_model_configs` table. The env vars serve as fallback.
