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

### Sample migration-target projects (siblings of this repo)

- `../test-altrix` — Jakarta EE 10 orders+payments app on legacy GCP Pub/Sub REST v1 (the migration SOURCE). Has a React+Vite UI (:5173), JPA persistence to Postgres `orders_db`, and `PUBSUB_EMULATOR_HOST` support (talks to `altrix-pubsub-emulator`).
- `../test-altrix-kafka` — hand-migrated Kafka reference baseline (:8081 backend, :5174 UI). Uses strict Kafka vocabulary.

**`mvn` is NOT installed locally.** Build these via the same Docker image the sandbox uses:
```bash
docker run --rm -v "C:/Users/SALAH/Projects/<proj>:/workspace" -v "C:/Users/SALAH/.m2:/root/.m2" -w /workspace maven:3.9-eclipse-temurin-21-alpine mvn -B clean package -DskipTests
```
Run the bundle: `java -jar target/<proj>-microbundle.jar --port 8081` (Payara Micro `--port` flag goes AFTER the jar; PowerShell mangles `-Dpayaramicro.port=`). On Windows Git Bash, prefix `docker exec` calls that contain `/opt/...` paths with `MSYS_NO_PATHCONV=1`.

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

The pipeline is driven by a **LangGraph4j stateful graph** (`infrastructure/workflow/MigrationWorkflowGraph`, implements `WorkflowExecutionPort`), NOT by simple bean ordering. `OrchestratorService.run()` does Phase 0 (RAG indexing) + Phase 0b (Project Mapper blueprint) then delegates to the graph. Graph topology:

```
START → context-analyzer → migration-planner ─(approval gate)─→
        core-migrator → semantic-validator ↔ sandbox-validator (retry ≤3) → report-generator → END
```

| Agent (`agent/impl/`) | Order | Tier | In → Out |
|---|---|---|---|
| `ContextAnalyzerAgent` | 1 | ANALYSIS | `ProjectContext → AnalysisReport` |
| `MigrationPlannerAgent` | 2 | ANALYSIS | `AnalysisReport → MigrationPlan` |
| `CoreMigratorAgent` (`typedCoreMigratorAgent`) | 3 | MIGRATION | `ApprovedPlan → MigrationArtifact` |
| `SemanticValidatorAgent` | 3 | — | `MigrationArtifact → MigrationArtifact` |
| `SandboxValidatorAgent` | 4 | — | `MigrationArtifact → ValidationReport` |
| `ReportGeneratorAgent` | 5 | ANALYSIS | `WorkflowOutcome → MigrationReport` |

When `workflow.require-approval.enabled=true` the graph halts at END after the planner (AWAITING_APPROVAL); `ResumeMigrationService` drives migrator→semantic-validator→sandbox-validator→reporter after a human approves via the session REST endpoints. Graph nodes are wired by qualifier in `BeanConfig`, not by the `getOrder()` value.

### SemanticValidator phase (between Core Migrator and Sandbox)

`SemanticValidatorAgent` (`semanticValidatorAgent`) verifies generated code semantically BEFORE the expensive Docker compile, so compilation is a final check rather than the primary debugging loop. `MigrationArtifact → MigrationArtifact` (may mutate). Order of operations in `execute()`:
1. **`DeterministicRepairEngine`** (`infrastructure/semantic/`) — mechanically-certain fixes first, no AI: adds missing Kafka imports for known types used-but-not-imported, removes forbidden+unused imports. Idempotent.
2. Aggregates findings into a `SemanticValidationReport` (`domain/model/semantic/`, categories CONTRACT / PUBSUB_LEAK / FORBIDDEN_IMPORT / MISSING_DEPENDENCY) by running the existing `ContractValidator` + `PubSubLeakValidator`, a KB-driven forbidden-import scan, and **`DependencyValidator`** (Kafka class imported but its Maven coordinate absent from pom.xml).

`KafkaMigrationKnowledgeBase` (`infrastructure/config/`, `@ConfigurationProperties("kafka-migration")`, YAML `kafka-migration-kb.yml` via `spring.config.import`) holds structured Pub/Sub→Kafka mappings (feature → kafkaEquivalent, requiredClasses/Imports/Dependencies, forbiddenSymbols) + class→dependency coordinates + global forbidden imports. Query helpers: `dependencyForClass` (`.*` wildcard), `allForbiddenImports`, `knownTypeImports`. `MigrationDecisionRegistry` (`domain/model/migration/`, `migration_decisions` JSONB table V24) is the session-scoped, append-only consistency store (type/class/dependency replacements; last-write-wins lookups).

### CoreMigratorAgent post-migration defense pipeline

The migrator rewrites files **one at a time** with free-tier models, so it hallucinates. `CoreMigratorAgent.execute()` runs migrated output through layered guards (in order) before the artifact reaches the sandbox — each reverts/repairs rather than failing the build:

1. **`PomSanitizer`** — strips hallucinated `<dependency>` entries not on the allow-list
2. **`ProjectSymbolValidator`** — reverts files importing intra-project classes that don't exist
3. **`ContractValidator` + `ContractRepairer`** (`infrastructure/contract/`) — JavaParser-based "Project Semantic Index". Catches cross-file inconsistency (file/class name mismatch, missing interface methods, unknown method calls, bad ctor arity, invalid `@Override`, package-private cross-package use). The repairer feeds violations back to the LLM for a minimal-patch fix.
4. **`PubSubLeakValidator` + `PubSubLeakRepairer`** (`infrastructure/leak/`) — output gate: any surviving `com.google.api.services.pubsub.*` import / `Pubsub` type / `projects().topics().publish()` chain is a migration failure. Repairer rewrites with pre-computed Kafka replacement suggestions.

`CoreMigratorAgent` also has inline guards: `firstDeniedImport` (config-driven deny-list with `.*` wildcards in `MigrationConfig.source.deniedImports`), `publicTypeMismatchingFilename`, `firstUsedButNotImported`, `stripLombokOnConstructor`, markdown-fence stripping, truncation detection. Both validators also register as `SandboxRunnerPort` beans (`ContractSandboxRunner` order -10, `PubSubLeakSandboxRunner` order -5) for defense-in-depth.

### Sandbox runners (Strategy + Composite)

`SandboxValidatorAgent` composes every `SandboxRunnerPort` bean sorted by `order()`. Negative orders run first: ContractSandboxRunner (-10), PubSubLeakSandboxRunner (-5), StaticSandboxRunner (0), DockerBootHealthRunner (50), DockerSandboxRunner (100, `mvn -q -B -DskipTests compile` in `maven:3.9-eclipse-temurin-21-alpine`), DockerTestRunner (150), MigrationQualityRunner (200). Docker runners are opt-in via `sandbox.docker.enabled=true`. Output persisted to `sandbox_logs`.

### RAG documentation corpus (NOT hard-coded anymore)

`DocumentationIngestionService` ingests reference docs into pgvector `code_embeddings` at startup. The corpus is **YAML-configured** in `DocumentationCorpusConfig` (`documentation.pages` + `documentation.allowed-domains` with host+path-prefix allow-list) — NOT a hard-coded list. Direction is strictly Pub/Sub→Kafka. `DomainAllowListValidator` gates every URL. Fetch goes through `McpDocumentationFetchAdapter` (when `ai.mcp.enabled=true`, calls an MCP `fetch`-style tool) or `HttpDocumentationFetchAdapter` (plain HTTPS fallback). `EmbeddingStorePort.deleteDocumentationNotIn()` prunes stale rows on startup so removed pages stop surfacing in similarity search.

### ProjectBlueprint (Index + Analyse combined — wired into the pipeline)

Replaces file-by-file blind migration with a project-wide semantic map. `ProjectMapperAgent` (`projectMapperAgent`, `ProjectContext → ProjectBlueprint`, order 1) uses **OpenRewrite LST** (lossless, type-attributed) to build a `SemanticGraph` (classes/methods/calls/inheritance/imports) plus per-file `BlueprintFile` slices (role, relationships, detected Pub/Sub features → Kafka targets), a `StackDetector` result, and a leaves-first `MigrationOrderResolver` order (Kahn's). Components live in `infrastructure/blueprint/` (`OpenRewriteLstParser`, `LstSemanticGraphBuilder`, `FeatureClassifier`, `StackDetector`, `MigrationOrderResolver`).

**Cutover (DONE).** `OrchestratorService.run()` Phase 0b runs `projectMapperAgent.execute(initial)` best-effort (null-guarded, never aborts the migration) and persists the blueprint under the session id. `CoreMigratorAgent` resolves the blueprint **once on its calling thread** (`resolveBlueprint()` via `ProjectBlueprintPort` + `SandboxContext.currentSessionId()`) and passes the immutable result down to the per-file workers — the migrator's worker pool can't see the `SandboxContext` ThreadLocal, so don't read it inside `migrateOneFile`. `buildBlueprintSection(path, blueprint)` prepends a "PROJECT MAP (this file)" block (role, extends/implements, dependsOn, calledBy, features→kafkaTarget, migration notes) to the per-file prompt + cache key. `OrchestratorService.run()` sets `SandboxContext` for the whole run (covers Phase 0b persistence, the inline-workflow migrator, and Docker runners) and clears it in a `finally`; the resume path sets it the same way.

**JSONB persistence.** `project_blueprints` (V23) + `migration_decisions` (V24) round-trip via JPA `AttributeConverter`s. The domain records stay **framework-free** — computed accessors (e.g. `BlueprintFile.isPassThrough()`, `MethodNode.signature()`) are excluded from JSON via Jackson **mix-ins** registered in the adapter (`BlueprintJacksonMixins.register(mapper)` in `ProjectBlueprintJsonConverter`), NOT `@JsonIgnore` on the records (that would leak Jackson into the domain and fail the `domain_must_not_depend_on_jackson` arch rule). Add a mix-in entry when you add a new computed accessor.

**Pending:** Stage 3 UI — `BlueprintStreamPort` WebSocket impl + REST + frontend Project Map panel (cytoscape.js).

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
