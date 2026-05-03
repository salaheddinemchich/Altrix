# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Repository Layout

```
altrix/
├── backend/               Gradle multi-module Java 21 backend
│   ├── gradlew            All Gradle commands run from here
│   ├── docker-compose.yml Infrastructure (Postgres, Redis, Kafka, MinIO, SonarQube, DefectDojo)
│   ├── .env / .env.example
│   ├── platform-common/   Pure Java library — shared domain types, no Spring
│   ├── platform-project/  Service (port 8082) — upload, detect, register projects
│   ├── platform-job/      Service (port 8083) — job lifecycle, status cache, download
│   └── platform-orchestrator/ Service (port 8084) — AI agent pipeline, WebSocket
└── frontend/              Angular 18 SSR app (early stage — routes not yet defined)
```

---

## Backend Commands

All Gradle commands must be run from `backend/`:

```bash
# Build all modules (skip tests)
./gradlew build -x test --parallel

# Run all tests with JaCoCo coverage
./gradlew :platform-common:test :platform-project:test :platform-job:test :platform-orchestrator:test --parallel --continue

# Run tests for a single module
./gradlew :platform-orchestrator:test

# Run a single test class
./gradlew :platform-orchestrator:test --tests "com.altrix.orchestrator.infra.ai.ProviderRouterTest"

# Run a single test method
./gradlew :platform-orchestrator:test --tests "com.altrix.orchestrator.infra.ai.ProviderRouterTest.falls_back_when_first_provider_throws"

# Run a service locally
./gradlew :platform-orchestrator:bootRun

# SonarQube analysis (requires SONAR_TOKEN and SONAR_HOST_URL in .env or -D flags)
./gradlew sonar -Dsonar.token=$SONAR_TOKEN -Dsonar.host.url=$SONAR_HOST_URL
```

### Infrastructure

```bash
cd backend
cp .env.example .env   # then fill in secrets
docker compose up -d   # starts Postgres, Redis, Kafka, MinIO, pgAdmin, Kafdrop, SonarQube, DefectDojo
```

Key infrastructure ports: Postgres 5432, Redis 6379, Kafka external 29092, MinIO API 9000 / console 9001, SonarQube 9003, Kafdrop 9002.

### Frontend

```bash
cd frontend
npm install
ng serve         # dev server at http://localhost:4200
ng build         # production build to dist/
ng test          # Karma unit tests
```

---

## Architecture

### Hexagonal (Ports & Adapters) + DDD + CQRS + Event-Driven

Every service follows the same layering:

```
domain/model/        Pure domain entities and value objects — no Spring, no JPA
domain/port/in/      Use-case interfaces (driving ports)
domain/port/out/     Repository and external-service interfaces (driven ports)
domain/service/      Domain service implementations
adapter/in/          REST controllers, Kafka listeners
adapter/out/         JPA adapters, Kafka producers, MinIO adapters, AI adapters
infrastructure/      Spring @Configuration, @ConfigurationProperties records
```

**Key rule**: the domain layer has zero framework imports. Infrastructure can be replaced without touching business logic.

### platform-common

No Spring — just shared types used by every service:
- `ProjectContext` — immutable record flowing through the agent pipeline; enriched via `with*()` methods
- `MigratedFile`, `DetectionResult`, `JobStatus`, `FileChangeType`, `ConfigFormatPreference`
- `BasePlatformException` hierarchy: `ProjectNotFoundException`, `JobNotFoundException`, `AgentFailureException`

### Event Flow Between Services

```
platform-project  →[project.registered]→         platform-job
platform-job      →[migration.job.created]→       platform-orchestrator
platform-orchestrator →[migration.job.status.update]→ platform-job
platform-job      →[migration.job.completed]→     (no consumer yet)
```

Kafka message format is pipe-delimited plain text (e.g. `"userId|storageKey"`, `"jobId|status|extra"`). Services never call each other directly over REST.

### platform-job CQRS Split

- **`JobCommandService`**: writes — creates jobs, drives status transitions, writes to Postgres + Redis, publishes Kafka events.
- **`JobQueryService`**: reads — checks Redis first (`job:status:{jobId}`, 24 h TTL), falls back to Postgres. Accepts `JobFilter` for listing.

### platform-orchestrator AI Routing

The AI subsystem is a multi-layer stack:

1. **`ProviderRouter`** — routes chat calls through an ordered provider chain. Reads the live provider list on every call (so config changes take effect immediately). Uses `CircuitBreakerRegistry` for lazy per-provider CBs.
2. **`ProviderRegistry`** — holds an `AtomicReference<List<RegisteredProvider>>` for lock-free live refresh. Calls `factory.isEnabled()` / `factory.build()` on each refresh; never has provider-specific code.
3. **`ProviderFactory` implementations** — one `@Component` per provider (`GroqProviderFactory`, `OpenAiProviderFactory`, `AnthropicProviderFactory`, `DeepSeekProviderFactory`, `OllamaProviderFactory`). Each calls `ProviderConfigResolver` to merge DB overrides on top of YAML defaults.
4. **`ProviderConfigResolver`** — merges: DB row (`provider_configs` table) wins over `application.yml`. Decrypts API keys via `AesGcmEncryptionAdapter`.
5. **`LangChain4jAiAdapter`** — implements `AiPort` (the domain port), mapping `chat()` → `ProviderTier.MIGRATION` and `chatFast()` → `ProviderTier.ANALYSIS`.

**Two-tier enum distinction**:
- `ProviderTier` — `ANALYSIS` (fast/cheap model) vs `MIGRATION` (powerful model) — selects *which* model to call on a provider.
- `ProviderCostTier` — `FREE` vs `PAID` — used by routing strategies to order providers.

**Routing strategies** (configured via `ai.routing.strategy`):
- `TIER_PREFERENCE` (default): orders by `ProviderCostTier` — `PAID_FIRST` (quality) or `FREE_FIRST` (cost).
- `EXPLICIT_ORDER`: uses `ai.routing.explicit-order` list.

**MCP (agentic tools)**: When `ai.mcp.enabled=true`, `McpClientAdapter` connects to configured MCP servers at startup, discovers tools via JSON-RPC 2.0, and `ProviderRouter.chatAgentic()` runs a model↔tool loop. Bean is `@ConditionalOnProperty`; injected as `Optional<McpToolsPort>` so it degrades gracefully.

### User-Configurable Provider Overrides

Users can override provider settings at runtime via `PUT /api/ai/providers/{providerId}` (no restart needed):
- API keys stored AES-256-GCM encrypted in `provider_configs` table. Encryption key is `PROVIDER_CONFIG_ENCRYPTION_KEY` env var (generate: `openssl rand -base64 32`).
- After a save, `ProviderRefreshPort.refreshProviders()` triggers `ProviderRegistry` to rebuild.
- API keys are write-only — never returned by `GET /api/ai/providers`.

### Agent Pipeline

`OrchestratorService` sorts all `AgentPort` beans by `getOrder()` and runs them sequentially:

| Order | Agent | ProviderTier | Output |
|-------|-------|-------------|--------|
| 1 | `ArchitectureAnalyzerAgent` | ANALYSIS | PubSub component map (topics, subscriptions, listener/publisher classes) |
| 3 | `CoreMigratorAgent` | MIGRATION | List of `MigratedFile` objects (rewritten source) |

Order gaps are intentional — new agents can be inserted between existing ones without renumbering.

### Coverage Exclusions

JaCoCo is configured to exclude `**/adapter/**`, `**/infrastructure/**`, and `**/*Application.class` from unit-test reports. These layers are intended to be covered by integration tests (Testcontainers). Domain coverage % is what's tracked.

### Flyway Schema Management

Each service manages its own schema independently:
- `platform-project` → `db/migration/V1__create_projects.sql`, history table `flyway_schema_history_project`
- `platform-job` → `db/migration/V1__create_migration_jobs.sql`
- `platform-orchestrator` → `db/migration/V1__create_provider_configs.sql`

### Adding a New AI Provider

1. Add a config record to `AiProvidersConfig` and a YAML block to `application.yml`.
2. Create `MyProviderFactory implements ProviderFactory` annotated `@Component`.
3. Implement `providerId()`, `costTier()`, `isEnabled()`, `build()` — call `ProviderConfigResolver` for DB merge.
4. Done. No changes to `ProviderRegistry`, `ProviderRouter`, or any other class.

---

## CI/CD Pipeline (`.github/workflows/ci.yml`)

| Stage | Tool | Trigger |
|-------|------|---------|
| 0. Secret scan | Gitleaks | all pushes/PRs |
| 1. Build & test | Gradle + JaCoCo | all pushes/PRs |
| 2. SAST | Semgrep (java, spring, owasp-top-ten, secrets) | all pushes/PRs |
| 3. SCA | Trivy filesystem | all pushes/PRs |
| 4. Docker build & push | GHCR | main push only |
| 5. Container scan | Trivy image | main push only |
| 6. Deploy staging | SSH + docker compose | main push + `STAGING_ENABLED=true` |
| 7. DAST | OWASP ZAP baseline | after staging deploy |
| 8. Findings aggregation | DefectDojo | after all scans (if `DEFECTDOJO_URL` set) |

Dockerfiles are at `backend/infra/docker/Dockerfile.platform-{project,job,orchestrator}`. Images are pushed to `ghcr.io/{owner}/altrix/{service}:latest`.
