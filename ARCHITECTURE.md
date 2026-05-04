# Altrix — Architecture & Component Guide

> **What Altrix does**: Altrix is an AI-powered platform that automatically migrates codebases from one technology stack to another. A user uploads a project ZIP, selects a target stack, and Altrix analyses the code, plans the migration, rewrites the files, and delivers the migrated project — entirely autonomously.

---

## Table of Contents

1. [The Problem](#the-problem)
2. [System Overview](#system-overview)
3. [Service Map](#service-map)
4. [Data Flow — End to End](#data-flow--end-to-end)
5. [AI Pipeline](#ai-pipeline)
6. [RAG — Retrieval-Augmented Generation](#rag--retrieval-augmented-generation)
7. [LangGraph — Stateful Workflow Graph](#langgraph--stateful-workflow-graph)
8. [Multi-Provider AI Routing](#multi-provider-ai-routing)
9. [Infrastructure](#infrastructure)
10. [Frontend](#frontend)
11. [Security & Observability](#security--observability)

---

## The Problem

Migrating a real codebase from one framework or stack to another is expensive, error-prone, and slow. Teams spend months manually rewriting files, updating dependencies, changing configuration formats, and re-wiring event-driven integrations. The patterns are repetitive but the volume is enormous.

**Altrix solves this with an autonomous AI agent pipeline** that:
- Understands the source architecture (frameworks, patterns, event topics, config formats)
- Plans the migration with full awareness of the target stack
- Rewrites every file with correct imports, annotations, and idioms for the target
- Produces a diff-reviewable, downloadable result

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Angular 18 SSR                              │
│   Upload UI · Job progress (WebSocket) · Diff viewer · Admin       │
└────────────────────────────┬────────────────────────────────────────┘
                             │ REST / WebSocket
         ┌───────────────────▼──────────────────────┐
         │           Spring Cloud Gateway            │  ← future (#24)
         │   Auth · CORS · Rate limiting · Routing   │
         └───┬──────────────┬──────────────┬─────────┘
             │              │              │
    ┌────────▼──────┐ ┌─────▼──────┐  ┌───▼────────────────┐
    │platform-project│ │platform-job│ │platform-orchestrator│
    │  port 8082     │ │  port 8083 │ │     port 8084       │
    └────────────────┘ └────────────┘ └────────────────────┘
             │              │              │
             └──────────────┴──────────────┘
                            │ Kafka events
                    ┌───────▼─────────┐
                    │  Infrastructure │
                    │  Postgres       │
                    │  Redis          │
                    │  MinIO          │
                    │  Kafka          │
                    └─────────────────┘
```

---

## Service Map

### `platform-common`
Pure Java library — no Spring, no framework dependencies.
Shared domain types used by every service:

| Type | Purpose |
|------|---------|
| `ProjectContext` | Immutable record that flows through the entire agent pipeline. Enriched via `with*()` methods at each stage. |
| `MigratedFile` | Represents one rewritten source file: original path, new path, content, diff summary, change type. |
| `MigrationAgent<I,O>` | Typed agent interface. Every agent declares its input type `I` and output type `O`. Returns sealed `AgentResult<T>`. |
| `AgentResult<T>` | Sealed: `Success(T value)` or `Failure(String reason, Throwable cause)`. |
| `DetectionResult` | Output of the architecture analyser: topics, subscriptions, listener/publisher classes. |
| `JobStatus` | Enum: `PENDING → IN_PROGRESS → COMPLETED / FAILED` |
| `BasePlatformException` hierarchy | `ProjectNotFoundException`, `JobNotFoundException`, `AgentFailureException` |

---

### `platform-project` (port 8082)
Handles project intake.

**Responsibilities:**
- Accept ZIP upload via REST (`POST /api/projects`)
- Store the raw ZIP in MinIO object storage
- Detect project metadata (language, frameworks, build tool) from file structure
- Persist project record to Postgres
- Publish `project.registered` Kafka event → triggers job creation

**Key domain flow:**
```
Upload ZIP → MinIO → DetectionService → ProjectRepository → Kafka[project.registered]
```

---

### `platform-job` (port 8083)
Owns the migration job lifecycle — CQRS split.

**`JobCommandService`** (writes):
- Creates job on `project.registered` event
- Drives status transitions: `PENDING → IN_PROGRESS → COMPLETED / FAILED`
- Writes to Postgres + Redis (24h TTL cache)
- Publishes `migration.job.created` → triggers orchestrator
- Publishes `migration.job.completed` on finish

**`JobQueryService`** (reads):
- Checks Redis first (`job:status:{jobId}`), falls back to Postgres
- Accepts `JobFilter` for listing and filtering

**Download endpoint:** `GET /api/jobs/{jobId}/download` streams the migrated ZIP from MinIO.

---

### `platform-orchestrator` (port 8084)
The intelligence of Altrix. Runs the AI agent pipeline.

**Responsibilities:**
- Consume `migration.job.created` event
- Run agents in order through the workflow graph
- Stream real-time progress to frontend via WebSocket
- Publish `migration.job.status.update` events to `platform-job`
- Store migrated files in MinIO

See [AI Pipeline](#ai-pipeline) and [LangGraph](#langgraph--stateful-workflow-graph) sections for full detail.

---

## Data Flow — End to End

```
1. User uploads project.zip via Angular UI
         │
         ▼
2. platform-project stores ZIP in MinIO, detects stack, persists project
   → Kafka: project.registered [projectId|storageKey]
         │
         ▼
3. platform-job receives event, creates MigrationJob (PENDING)
   → Kafka: migration.job.created [jobId|projectId|storageKey]
         │
         ▼
4. platform-orchestrator receives event, builds ProjectContext
         │
         ▼
5. [INDEXING] CodeIndexingAgent reads ZIP from MinIO, chunks and embeds
   all source files, stores vectors in pgvector                       ← RAG #172
         │
         ▼
6. [GRAPH NODE 1] ArchitectureAnalyzerAgent (ANALYSIS tier)          ← LangGraph #173
   - Retrieves relevant chunks from vector store (RAG)
   - Calls AI: "identify all Kafka topics, subscriptions, listener
     and publisher classes in this project"
   - Returns: DetectionResult → enriches ProjectContext
         │
         ▼
7. [GRAPH NODE 2] CoreMigratorAgent (MIGRATION tier)
   - Retrieves relevant chunks from vector store (RAG)
   - Calls AI: "rewrite each file for target stack, using the
     detected architecture as context"
   - Returns: List<MigratedFile> → enriches ProjectContext
   - If validation fails → retry loop with error feedback (LangGraph)
         │
         ▼
8. [GRAPH NODE N] Future agents (ValidatorAgent, TestGeneratorAgent,
   ConfigMigratorAgent, ReportGeneratorAgent...)
         │
         ▼
9. OrchestratorService packages MigratedFiles → ZIP → MinIO
   → Kafka: migration.job.status.update [jobId|COMPLETED|storageKey]
         │
         ▼
10. platform-job updates job status, Angular WebSocket receives COMPLETED
    User downloads migrated project ZIP
```

---

## AI Pipeline

### Agent Interface

Every agent implements `MigrationAgent<I, O>`:

```java
public interface MigrationAgent<I, O> {
    AgentResult<O> execute(I input, ProjectContext context);
    int getOrder();
    String getAgentName();
}
```

### Current Agents

| Order | Agent | Input | Output | AI Tier |
|-------|-------|-------|--------|---------|
| 1 | `ArchitectureAnalyzerAgent` | `ProjectContext` | `DetectionResult` | ANALYSIS (fast) |
| 3 | `CoreMigratorAgent` | `ProjectContext` | `List<MigratedFile>` | MIGRATION (powerful) |

Order gaps are intentional — agents can be inserted without renumbering.

### Planned Agents

| Order | Agent | Purpose |
|-------|-------|---------|
| 0 | `CodeIndexingAgent` | Chunk + embed source files into vector store (RAG) |
| 2 | `MigrationPlannerAgent` | Generate a step-by-step migration plan before rewriting |
| 4 | `ValidatorAgent` | Compile-check migrated files, detect missing imports |
| 5 | `TestGeneratorAgent` | Generate unit tests for migrated classes |
| 6 | `ConfigMigratorAgent` | Migrate application.properties → application.yml or vice versa |
| 7 | `ReportGeneratorAgent` | Narrative summary of what was changed and why |

---

## RAG — Retrieval-Augmented Generation

**Issue:** [#172](https://github.com/salaheddinemchich/Altrix/issues/172)

### Why RAG
Large codebases exceed model context windows. Instead of sending all source files in every prompt, RAG indexes the codebase and retrieves only the semantically most relevant chunks for each agent call.

### Architecture

```
                    ┌─────────────────────────────────┐
                    │       CodeIndexingAgent          │
                    │  (runs once after project upload) │
                    └──────────────┬──────────────────┘
                                   │ chunk + embed
                    ┌──────────────▼──────────────────┐
                    │    pgvector (Postgres ext.)      │
                    │  table: code_embeddings          │
                    │  columns: file_path, chunk_text, │
                    │           embedding vector,      │
                    │           content_hash           │
                    └──────────────┬──────────────────┘
                                   │ similarity search
                    ┌──────────────▼──────────────────┐
                    │       RetrievalPort              │
                    │   (driven port in domain)        │
                    └──────────────┬──────────────────┘
                                   │ top-k chunks
                    ┌──────────────▼──────────────────┐
                    │     Agent prompts enriched       │
                    │  with retrieved context          │
                    └─────────────────────────────────┘
```

### Key Design Decisions
- **No new service** — RAG lives entirely in `platform-orchestrator`
- **pgvector** is used as the vector store — it runs on the existing Postgres instance (just needs the extension enabled)
- **Content hash deduplication** — files with unchanged content are not re-embedded, saving cost
- **Chunk granularity** — Java files are chunked at class level; YAML/properties at block level
- **LangChain4j APIs**: `EmbeddingModel` → `EmbeddingStore` → `EmbeddingStoreIngestor` / `EmbeddingStoreRetriever`

---

## LangGraph — Stateful Workflow Graph

**Issue:** [#173](https://github.com/salaheddinemchich/Altrix/issues/173)

### Why LangGraph
The current sequential agent loop has no branching, no retry logic, and no checkpointing. A production migration pipeline needs conditional routing, parallel execution, and the ability to resume after failure.

### Library
**`langgraph4j`** (`io.github.bsorrentino:langgraph4j-core`) — the official Java port of Python's LangGraph. Integrates directly with LangChain4j.

### Workflow Graph

```
                    ┌─────────────┐
                    │    START    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────────────┐
                    │  CodeIndexingAgent  │  (embeds codebase into pgvector)
                    └──────┬──────────────┘
                           │
                    ┌──────▼──────────────────────┐
                    │ ArchitectureAnalyzerAgent    │
                    └──────┬───────────────────────┘
                           │
              ┌────────────▼────────────┐
              │   has Kafka topics?     │  ← conditional edge
              └──────┬──────────┬───────┘
                   YES          NO
                    │            │
         ┌──────────▼──┐   ┌────▼──────────────┐
         │CoreMigrator │   │SkipKafkaSubgraph  │
         │    Agent    │   └────────────────────┘
         └──────┬───────┘
                │
        ┌───────▼────────┐
        │ output valid?  │  ← conditional edge
        └───┬────────┬───┘
          YES       NO (max 3 retries)
           │         │
           │    ┌────▼──────────────────┐
           │    │ loop back with error  │
           │    │ feedback in context   │
           │    └───────────────────────┘
    ┌──────▼──────┐
    │  [future    │
    │   agents]   │
    └──────┬──────┘
           │
       ┌───▼───┐
       │  END  │
       └───────┘
```

### State
```java
record MigrationState(
    ProjectContext context,
    List<String>   errors,          // accumulated for retry feedback
    Map<String, Boolean> agentsDone,
    int retryCount
) {}
```

### Checkpointing
Graph state is persisted to Redis after each node completes. If the orchestrator crashes mid-migration, the graph resumes from the last successful checkpoint — not from scratch.

---

## Multi-Provider AI Routing

The AI subsystem is provider-agnostic. Any agent calls `AiPort.chat()` or `AiPort.chatFast()` — the routing layer handles the rest.

```
AiPort (domain port)
    │
LangChain4jAiAdapter
    │
ProviderRouter  ──── reads live provider list on every call
    │            ├── Tier Bulkhead   (semaphore: ANALYSIS=3, MIGRATION=5 concurrent)
    │            ├── per-provider Retry  (exponential backoff + jitter, transient-only)
    │            └── per-provider CircuitBreaker  (sliding window, lazy-init)
    │
ProviderRegistry ─── AtomicReference<List<RegisteredProvider>> for lock-free refresh
    │
ProviderFactory implementations (one @Component per provider):
    ├── GroqProviderFactory          FREE   — Groq cloud (llama-3.x models)
    ├── DeepSeekProviderFactory      FREE   — DeepSeek API
    ├── OllamaProviderFactory        FREE   — local Ollama (warmup on startup optional)
    ├── NvidiaProviderFactory        PAID   — NVIDIA NIM (integrate.api.nvidia.com/v1)
    ├── OpenAiProviderFactory        PAID   — OpenAI
    └── AnthropicProviderFactory     PAID   — Anthropic Claude
```

**Provider tiers:**
- `ANALYSIS` — fast/cheap model (e.g. `llama3.2:3b`, `llama-3.1-8b-instruct`) for structural scans
- `MIGRATION` — powerful model (e.g. `claude-sonnet-4-6`, `gpt-4o`, `nemotron-70b`) for code rewriting

**Cost tiers:** `FREE` (Groq, DeepSeek, Ollama) vs `PAID` (NVIDIA, OpenAI, Anthropic) — used by routing strategies.

**Routing strategies** (configured via `ai.routing.strategy`):
- `TIER_PREFERENCE` (default): `PAID_FIRST` (quality) or `FREE_FIRST` (cost)
- `EXPLICIT_ORDER`: fixed list in `ai.routing.explicit-order`

**Resilience stack (per call, since Sprint 1):**
- **Bulkhead** — semaphore limits concurrent AI calls per tier (`AI_BULKHEAD_ANALYSIS`, `AI_BULKHEAD_MIGRATION`)
- **Retry** — retries transient errors (429, 5xx, I/O) with exponential backoff + jitter (`AI_RETRY_MAX`, `AI_RETRY_WAIT_MS`)
- **CircuitBreaker** — short-circuits broken providers after N failures (`AI_CB_FAILURE_RATE`, `AI_CB_WAIT_SECONDS`)

**Runtime config:** Users can override provider settings via `PUT /api/ai/providers/{providerId}` without restart. API keys stored AES-256-GCM encrypted in `provider_configs` table.

**NVIDIA NIM:** get an API key at https://build.nvidia.com/models, set `NVIDIA_API_KEY` + `NVIDIA_ENABLED=true`.

---

## Infrastructure

| Component | Port | Purpose |
|-----------|------|---------|
| Postgres + pgvector | 5432 | Relational data + vector embeddings |
| Redis | 6379 | Job status cache (24h TTL) + graph checkpoints |
| Kafka | 29092 | Async event bus between services |
| MinIO | 9000/9001 | Object storage for project ZIPs and migrated output |
| SonarQube | 9003 | Static analysis dashboard |
| DefectDojo | 8089 | Security findings aggregation |

**Kafka topics:**

| Topic | Producer | Consumer |
|-------|----------|----------|
| `project.registered` | platform-project | platform-job |
| `migration.job.created` | platform-job | platform-orchestrator |
| `migration.job.status.update` | platform-orchestrator | platform-job |
| `migration.job.completed` | platform-job | (no consumer yet) |

Message format: pipe-delimited plain text — e.g. `"jobId|status|storageKey"`.

### Schema Management (Flyway)

Each service manages its own schema independently:

| Service | Migration file | Purpose |
|---------|---------------|---------|
| platform-project | `V1__create_projects.sql` | Projects table |
| platform-project | `V2__optimize_projects_indexes.sql` | Composite + partial + covering indexes |
| platform-job | `V1__create_migration_jobs.sql` | Jobs table |
| platform-job | `V2__optimize_jobs_indexes.sql` | Composite + partial + covering indexes |
| platform-orchestrator | `V1__create_provider_configs.sql` | Provider config overrides (encrypted keys) |
| platform-orchestrator | `V2__create_code_embeddings.sql` | pgvector embeddings table (RAG) |
| platform-orchestrator | `V3__optimize_embeddings.sql` | BRIN + partial index on embeddings |
| platform-orchestrator | `V4__create_token_usage.sql` | Token usage analytics per call |

---

## Frontend

**Angular 18 SSR** — standalone components, signals, `inject()`, new control flow (`@if` / `@for`).

| Route | Purpose |
|-------|---------|
| `/` | Landing — upload project, select target stack |
| `/jobs` | Job list with status polling |
| `/jobs/:id` | Real-time progress via WebSocket + diff viewer |
| `/admin/providers` | AI provider config — keys, tiers, routing strategy |
| `/admin/analytics` | Token usage analytics — `GET /api/ai/token-usage` |

WebSocket endpoint: `ws://localhost:8084/ws/migration/{jobId}` — streams agent progress events in real-time.

---

## Security & Observability

### CI/CD Pipeline (`.github/workflows/ci.yml`)

| Stage | Tool | Trigger |
|-------|------|---------|
| Secret scan | Gitleaks | all pushes/PRs |
| Build & test + coverage | Gradle + JaCoCo | all pushes/PRs |
| SAST | Semgrep (`p/java`, `p/owasp-top-ten`, `p/secrets`) | all pushes/PRs |
| SCA | Trivy filesystem | all pushes/PRs |
| Docker build & push | GHCR | main push only |
| Container scan | Trivy image | main push only |
| Deploy staging | SSH + docker compose | main push + `STAGING_ENABLED=true` |
| DAST | OWASP ZAP baseline | after staging deploy |
| Findings aggregation | DefectDojo | after all scans |

### Coverage
JaCoCo excludes `adapter/**`, `infrastructure/**`, and `*Application.class` from unit-test reports. These layers are covered by Testcontainers integration tests (planned). Domain coverage % is the tracked metric.

### Architecture Tests (planned — #30)
ArchUnit rules will enforce hexagonal layer boundaries, CQRS separation, and naming conventions — failing the CI build on any violation.
