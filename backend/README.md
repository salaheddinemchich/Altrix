# PubSub → Kafka Migrator

An AI-powered platform that automatically migrates Java Spring Boot applications from **Google Cloud PubSub** to **Apache Kafka**. Users upload a ZIP of their existing project and receive a migrated ZIP with all PubSub references replaced by their Kafka equivalents.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
  - [Architectural Patterns](#architectural-patterns)
  - [Module Structure](#module-structure)
- [Services](#services)
  - [platform-common](#platform-common)
  - [platform-project](#platform-project-port-8082)
  - [platform-job](#platform-job-port-8083)
  - [platform-orchestrator](#platform-orchestrator-port-8084)
- [Data Flow](#data-flow)
- [AI Agent Pipeline](#ai-agent-pipeline)
- [Kafka Topics](#kafka-topics)
- [REST Endpoints](#rest-endpoints)
- [Technology Stack](#technology-stack)
- [Infrastructure](#infrastructure)
- [Configuration](#configuration)
- [Running the Project](#running-the-project)

---

## Overview

The platform transforms PubSub-based Spring Boot projects by:

1. Accepting a ZIP upload from the user
2. Detecting the build system, configuration format, and framework
3. Creating a migration job and publishing it to Kafka
4. Running an AI agent pipeline that analyzes and rewrites all relevant files
5. Storing the migrated ZIP in object storage
6. Broadcasting real-time progress via WebSocket
7. Returning the migrated ZIP for download

---

## Architecture

### Architectural Patterns

| Pattern | Application |
|---------|-------------|
| **Hexagonal Architecture** (Ports & Adapters) | Each service has a pure-Java domain core surrounded by adapter implementations; infrastructure can be swapped without touching business logic |
| **DDD (Domain-Driven Design)** | Entities with identity-based equality, value objects, domain services, repository pattern, domain events |
| **CQRS** | Separate command and query services in `platform-job` (`JobCommandService` / `JobQueryService`) |
| **Event-Driven** | Services communicate exclusively via Kafka; no direct service-to-service REST calls |
| **Immutable Data Flow** | `ProjectContext` is never mutated; each agent returns an enriched copy |

### Module Structure

```
altrix/
├── platform-common/        Pure Java domain kernel — shared types, no Spring
├── platform-project/       (port 8082) File upload, detection, project registration
├── platform-job/           (port 8083) Job lifecycle management, download endpoint
├── platform-orchestrator/  (port 8084) AI agent pipeline, WebSocket progress
└── infra/                  Docker Compose, Kafka config, environment files
```

---

## Services

### platform-common

A plain Java library (no Spring, no JPA) containing the shared domain model used by all services.

**Key types:**

| Type | Kind | Description |
|------|------|-------------|
| `ProjectContext` | Immutable record | The single state object flowing through the agent pipeline; carries job/project IDs, detection results, discovered PubSub components, and migrated files |
| `DetectionResult` | Value object | Build system (`MAVEN`, `GRADLE_KOTLIN`, `GRADLE_GROOVY`), config format (`YAML`, `PROPERTIES`), and framework (`SPRING_BOOT`, etc.) |
| `MigratedFile` | Value object | One transformed file: original path, new path, full content, change type, diff summary |
| `JobStatus` | Enum | `PENDING` → `ANALYZING` → `MIGRATING` → `DONE` (or `FAILED` / `CANCELLED`) |
| `FileChangeType` | Enum | `CREATED`, `MODIFIED`, `DELETED` |
| `ConfigFormatPreference` | Enum | `KEEP_ORIGINAL`, `FORCE_YAML`, `FORCE_PROPERTIES` |

**Exception hierarchy:**

```
BasePlatformException
├── ProjectNotFoundException
├── JobNotFoundException
└── AgentFailureException
```

---

### platform-project (port 8082)

Handles file uploads, build-system detection, project persistence, and publishes the first Kafka event.

#### Domain Model

**`Project`** (DDD Entity — identity by `id`):
- `userId`, `name`, `storageKey` (MinIO reference)
- `buildSystem`, `configFormat`, `framework` (from detection)
- Status: `PENDING` → `READY` (or `ERROR`)
- Immutable transitions: `withDetectionApplied()`, `withError()`

#### Ports

| Port | Direction | Contract |
|------|-----------|----------|
| `UploadProjectUseCase` | Input (driving) | `upload(userId, fileName, zipBytes, sizeBytes, formatPref) → Project` |
| `GetProjectQuery` | Input (driving) | `findById(id)`, `findAllByUserId(userId)` |
| `ProjectRepository` | Output (driven) | JPA persistence |
| `FileStoragePort` | Output (driven) | MinIO store / retrieve |
| `ProjectEventPublisher` | Output (driven) | Kafka publishing |

#### Adapters

| Adapter | Type | Detail |
|---------|------|--------|
| `ProjectController` | REST | `POST /api/v1/projects/upload` (multipart), `GET /api/v1/projects/{id}`, `GET /api/v1/projects` |
| `ProjectPersistenceAdapter` | JPA | Maps `Project` ↔ `ProjectJpaEntity`; `projects` table |
| `MinioFileStorageAdapter` | Object storage | Bucket `altrix-projects`, prefix `uploads/`, UUID-named keys |
| `KafkaProjectEventAdapter` | Kafka producer | Topic `project.registered`, format `key=projectId value="userId|storageKey"` |

#### Detection

`BuildSystemDetector` inspects the ZIP contents to determine:
- **Build system**: presence of `pom.xml` (Maven) or `build.gradle.kts` / `build.gradle` (Gradle)
- **Config format**: `.yml` / `.yaml` vs `.properties` files
- **Framework**: Spring Boot starter presence, `@SpringBootApplication`, etc.

---

### platform-job (port 8083)

Manages the full job lifecycle: creates jobs from Kafka events, tracks status transitions, caches status in Redis, and serves the final download.

#### Domain Model

**`MigrationJob`** (DDD Entity — identity by `id`):
- `projectId`, `userId`, `projectStorageKey`, `outputStorageKey`
- `configFormatPreference`, `errorMessage`
- Status machine: `PENDING → ANALYZING → MIGRATING → DONE | FAILED | CANCELLED`
- Terminal states: `DONE`, `FAILED`, `CANCELLED`
- Transition methods: `startAnalyzing()`, `startMigrating()`, `complete(outputStorageKey)`, `fail(reason)`, `cancel()`

#### Ports

| Port | Direction | Contract |
|------|-----------|----------|
| `CreateJobUseCase` | Input | `createJob(projectId, userId, storageKey, formatPref) → MigrationJob` |
| `UpdateJobStatusUseCase` | Input | `markAnalyzing`, `markMigrating`, `markDone`, `markFailed` |
| `GetJobQuery` | Input | `findById`, `getStatus`, `findAll(filter)` |
| `JobRepository` | Output | JPA persistence |
| `JobCachePort` | Output | Redis, key `job:status:{jobId}`, 24 h TTL |
| `JobEventPublisher` | Output | Kafka publishing |

#### CQRS Services

- **`JobCommandService`**: Handles writes — creates jobs, persists transitions, caches in Redis, publishes events.
- **`JobQueryService`**: Handles reads — checks Redis first (24 h TTL), falls back to PostgreSQL. Supports `JobFilter` (userId, status).

#### Adapters

| Adapter | Type | Detail |
|---------|------|--------|
| `JobCommandController` | REST | `POST /api/v1/jobs` |
| `JobQueryController` | REST | `GET /api/v1/jobs/{id}`, `GET /api/v1/jobs/{id}/status`, `GET /api/v1/jobs`, `GET /api/v1/jobs/{id}/download` |
| `ProjectRegisteredListener` | Kafka consumer | Consumes `project.registered`, creates job |
| `KafkaJobEventAdapter` | Kafka producer | Topics: `migration.job.created`, `migration.job.completed`, `migration.job.status.update` |
| `RedisJobCacheAdapter` | Cache | `StringRedisTemplate`, prefix `job:status:`, 24 h TTL |
| `JobPersistenceAdapter` | JPA | Maps `MigrationJob` ↔ `MigrationJobJpaEntity`; `migration_jobs` table |
| `MinioJobStorageAdapter` | Object storage | Reads migrated ZIP for download streaming |

---

### platform-orchestrator (port 8084)

The intelligence hub. Consumes job-created events and runs the sequential AI agent pipeline that transforms PubSub code into Kafka code.

#### Ports

| Port | Direction | Contract |
|------|-----------|----------|
| `RunPipelineUseCase` | Input | `run(context: ProjectContext) → ProjectContext` |
| `AgentPort` | Output | `getName()`, `getOrder(): int`, `execute(context) → context` |
| `AiPort` | Output | `chat(systemPrompt, userContent) → String` |
| `FileReaderPort` | Output | `readSourceFiles(storageKey) → Map<path, content>` |
| `MigratedFileStoragePort` | Output | `storeMigratedZip(jobId, files) → outputStorageKey` |
| `JobStatusUpdatePort` | Output | `markAnalyzing`, `markMigrating`, `markDone`, `markFailed` |
| `ProgressNotifierPort` | Output | `notify(jobId, agentName, status, message)` |

#### Domain Service

**`OrchestratorService`**:
1. Sorts all `AgentPort` beans by `getOrder()`
2. Runs each agent sequentially, passing the enriched `ProjectContext` forward
3. Updates job status at known pipeline milestones (order 1 → ANALYZING, order 3 → MIGRATING)
4. Stores the migrated ZIP in MinIO on completion
5. Broadcasts per-agent progress via WebSocket
6. Catches any failure, marks job FAILED, and does not rethrow (prevents Kafka retry loops)

#### Adapters

| Adapter | Type | Detail |
|---------|------|--------|
| `JobCreatedListener` | Kafka consumer | Consumes `migration.job.created`, launches pipeline |
| `GroqAiAdapter` | AI provider | WebClient → Groq API, model `llama-3.3-70b-versatile`, temperature 0.1, max 4096 tokens |
| `MinioFileReaderAdapter` | Object storage | Extracts `.java`, `.kt`, `.yml`, `.yaml`, `.properties`, `.gradle`, `pom.xml` from ZIP; 128 KB/file cap |
| `MinioMigratedFileStorageAdapter` | Object storage | Packs `MigratedFile` list into ZIP at `migrated/{jobId}/output.zip` |
| `WebSocketProgressAdapter` | WebSocket | STOMP broker, clients subscribe to `/topic/jobs/{jobId}` |
| `KafkaJobStatusAdapter` | Kafka producer | Publishes status updates to `migration.job.status.update` |

---

## AI Agent Pipeline

Agents are discovered by the `OrchestratorService` via Spring's dependency injection and sorted by their `getOrder()` value.

### Agent 1 — ArchitectureAnalyzerAgent

| Property | Value |
|----------|-------|
| **Order** | 1 |
| **Input** | All `.java` and `.kt` source files from the uploaded ZIP |
| **Task** | Identify every PubSub component |
| **AI call** | System prompt: *"Identify every PubSub component"* + file contents |
| **Output (JSON)** | `{ pubSubTopics, pubSubSubscriptions, listenerClasses, publisherClasses }` |
| **Context enrichment** | `withPubSubTopics()`, `withPubSubSubscriptions()`, `withListenerClasses()`, `withPublisherClasses()` |

### Agent 3 — CoreMigratorAgent

| Property | Value |
|----------|-------|
| **Order** | 3 |
| **Input** | Full source map (Java, Kotlin, config, build files) + architecture analysis from Agent 1 |
| **Task** | Rewrite all PubSub usages to Kafka equivalents |
| **Output (JSON)** | Array of `MigratedFile` objects |
| **Context enrichment** | `withMigratedFiles()` |

**Migration rules applied by CoreMigratorAgent:**

| PubSub (before) | Kafka (after) |
|-----------------|---------------|
| `@PubSubListener` | `@KafkaListener(topics = "...", groupId = "migrated-group")` |
| `PubSubTemplate` | `KafkaTemplate<String, String>` |
| `message.publish(...)` | `kafkaTemplate.send(topic, payload)` |
| `AcknowledgeablePubsubMessage` | `String payload` + `Acknowledgment ack` |
| `ProjectSubscriptionName` | Removed entirely |
| `spring-cloud-gcp-starter-pubsub` dependency | `spring-kafka` |
| GCP credentials in config | Removed |
| (no kafka config) | `spring.kafka.bootstrap-servers` added |
| Config format | Preserved (or changed per `ConfigFormatPreference`) |

The AI provider is fully abstracted behind `AiPort`. Switching from Groq to OpenAI, Ollama, or Together AI requires only writing a new `AiPort` adapter — zero domain changes.

---

## Data Flow

```
 User
  │
  │  POST /api/v1/projects/upload (ZIP)
  ▼
┌─────────────────────┐
│   platform-project  │  (8082)
│                     │──► MinIO: uploads/{uuid}.zip
│  Detect build system│
│  Persist project    │──► PostgreSQL: projects
│  Publish event      │──► Kafka: project.registered
└─────────────────────┘       key=projectId, value="userId|storageKey"
                                       │
                                       ▼
                        ┌─────────────────────┐
                        │   platform-job      │  (8083)
                        │                     │
                        │  Create job         │──► PostgreSQL: migration_jobs
                        │  Cache status       │──► Redis: job:status:{jobId}
                        │  Publish event      │──► Kafka: migration.job.created
                        └─────────────────────┘       key=jobId, value="projectId|storageKey"
                                                               │
                                                               ▼
                                              ┌───────────────────────────────┐
                                              │   platform-orchestrator       │  (8084)
                                              │                               │
                                              │   Agent 1: Analyze PubSub    │
                                              │     ├─ Read ZIP from MinIO   │
                                              │     └─ AI: map components    │
                                              │                               │
                                              │   Agent 3: Migrate to Kafka  │
                                              │     ├─ Read ZIP from MinIO   │
                                              │     └─ AI: rewrite files     │
                                              │                               │
                                              │   Store migrated ZIP          │──► MinIO: migrated/{jobId}/output.zip
                                              │   Update job status           │──► Kafka: migration.job.status.update
                                              │   Broadcast progress          │──► WebSocket: /topic/jobs/{jobId}
                                              └───────────────────────────────┘
                                                               │
                                                               ▼
                                              ┌─────────────────────┐
                                              │   platform-job      │  (8083)
                                              │                     │
                                              │  Consume status upd │
                                              │  Mark job DONE      │──► PostgreSQL
                                              │  Update Redis cache │──► Redis
                                              └─────────────────────┘

 User
  │
  │  GET /api/v1/jobs/{jobId}/download
  ▼
┌─────────────────────┐
│   platform-job      │──► MinIO: migrated/{jobId}/output.zip ──► StreamingResponseBody
└─────────────────────┘
```

---

## Kafka Topics

| Topic | Producer | Consumer | Format |
|-------|----------|----------|--------|
| `project.registered` | platform-project | platform-job | `key=projectId`, `value="userId\|storageKey"` |
| `migration.job.created` | platform-job | platform-orchestrator | `key=jobId`, `value="projectId\|storageKey"` |
| `migration.job.status.update` | platform-orchestrator | platform-job | `key=jobId`, `value="{status}\|{extra}"` |
| `migration.job.completed` | platform-job | — | `key=jobId`, `value="{status}\|{outputStorageKey}"` |

---

## REST Endpoints

### platform-project (8082)

| Method | Path | Headers | Body | Response |
|--------|------|---------|------|----------|
| `POST` | `/api/v1/projects/upload` | — | multipart: `file`, `userId`, `projectName`, `configFormatPreference` | `ProjectResponse` |
| `GET` | `/api/v1/projects/{projectId}` | — | — | `ProjectResponse` |
| `GET` | `/api/v1/projects` | `X-User-Id` | — | `List<ProjectResponse>` |

### platform-job (8083)

| Method | Path | Params | Response |
|--------|------|--------|----------|
| `POST` | `/api/v1/jobs` | — | `JobResponse` |
| `GET` | `/api/v1/jobs/{jobId}` | — | `JobResponse` |
| `GET` | `/api/v1/jobs/{jobId}/status` | — | `JobStatusResponse` |
| `GET` | `/api/v1/jobs` | `userId`, `status` | `List<JobResponse>` |
| `GET` | `/api/v1/jobs/{jobId}/download` | — | ZIP (streaming) |

### platform-orchestrator (8084)

| Protocol | Destination | Message |
|----------|-------------|---------|
| WebSocket (STOMP) | `/topic/jobs/{jobId}` | `{ jobId, agentName, status, message }` |

---

## Technology Stack

| Category | Technology | Role |
|----------|-----------|------|
| Language | Java 21 | Core language |
| Build | Gradle (Kotlin DSL) | Build automation |
| Web | Spring Boot 3.3.4 | REST + WebSocket |
| Database | PostgreSQL 16 | Project and job persistence |
| Cache | Redis 7.2 | Job status cache (24 h TTL) |
| Messaging | Apache Kafka 3.9 | Event-driven async communication |
| Object storage | MinIO | ZIP file storage (uploads & migrations) |
| AI | Groq (llama-3.3-70b-versatile) | LLM for code transformation |
| ORM | JPA / Hibernate | Object-relational mapping |
| Schema migration | Flyway | Versioned DB migrations per service |
| HTTP client | Spring WebFlux (WebClient) | Reactive calls to AI API |
| WebSocket | Spring WebSocket + STOMP | Real-time progress broadcasting |
| Validation | Jakarta Validation | Bean validation |
| Observability | Spring Actuator | Health checks, metrics |
| Utilities | Lombok | Code generation |

---

## Infrastructure

All infrastructure is defined in `infra/docker-compose.yml`.

| Service | Port(s) | Purpose |
|---------|---------|---------|
| PostgreSQL | 5432 | Persistent database |
| pgAdmin | 5050 | Database management UI |
| Redis | 6379 | Job status cache |
| Redis Insight | 8001 | Cache management UI |
| Apache Kafka | 9092 (internal), 29092 (external) | Message broker |
| Kafdrop | 9002 | Kafka management UI |
| MinIO | 9000 (API), 9001 (console) | Object storage |

All services are connected via the `altrix-net` bridge network and use named Docker volumes for persistence (`postgres_data`, `minio_data`).

---

## Configuration

Environment variables are supplied via `infra/.env`:

```env
POSTGRES_USER=...
POSTGRES_PASSWORD=...
POSTGRES_DB=altrix_db

REDIS_PASSWORD=...

MINIO_ROOT_USER=...
MINIO_ROOT_PASSWORD=...

AI_PROVIDER_BASE_URL=https://api.groq.com/openai/v1
AI_PROVIDER_API_KEY=...
AI_PROVIDER_MODEL=llama-3.3-70b-versatile
```

Each service has its own `application.yml`. Key settings per service:

- **platform-project**: `max-file-size: 50MB`, Flyway table `flyway_schema_history_project`, MinIO bucket `altrix-projects`
- **platform-job**: Redis TTL 24 h, Kafka consumer group `platform-job`
- **platform-orchestrator**: Kafka consumer group `platform-orchestrator`, AI temperature `0.1`, max tokens `4096`, per-file read cap `128 KB`

Flyway migrations are isolated per service (separate history tables) to allow independent schema evolution.

---

## Running the Project

### 1. Start infrastructure

```bash
cd infra
cp .env.example .env   # fill in secrets
docker compose up -d
```

### 2. Start services (in any order)

```bash
# Terminal 1
./gradlew :platform-project:bootRun

# Terminal 2
./gradlew :platform-job:bootRun

# Terminal 3
./gradlew :platform-orchestrator:bootRun
```

### 3. Upload a project

```bash
curl -X POST http://localhost:8082/api/v1/projects/upload \
  -F "file=@my-pubsub-project.zip" \
  -F "userId=user-1" \
  -F "projectName=MyApp" \
  -F "configFormatPreference=KEEP_ORIGINAL"
```

The response contains a `projectId`. A job is automatically created and the pipeline begins.

### 4. Poll job status

```bash
curl http://localhost:8083/api/v1/jobs/{jobId}/status
```

Or subscribe to the WebSocket topic `ws://localhost:8084/ws` → `/topic/jobs/{jobId}` for real-time updates.

### 5. Download migrated project

```bash
curl -O http://localhost:8083/api/v1/jobs/{jobId}/download
```

### Get a free Groq API key

Go to https://console.groq.com → create account → API Keys → Create key.
Add it to your `.env` as `AI_PROVIDER_API_KEY`.
