# Altrix — Handoff & Recovery Guide

> **Purpose of this file:** rebuild full project context from scratch after a fresh OS install. Read top-to-bottom on first day back. Everything important lives somewhere below.

---

## 0. Where we left off (LATEST — read first)

* **Sample project to migrate:** GitHub repo [`salaheddinemchich/test-altrix`](https://github.com/salaheddinemchich/test-altrix) — Jakarta EE 10 / Maven, Orders + Payments CRUD, uses the legacy Google Cloud Pub/Sub REST v1 API. Local copy at `~/Documents/test/`.
* **End-to-end pipeline works** up to but not through migration:
  * GitHub import → project saved with `eligibleForMigration=true`, technologies `JAKARTA_EE,MAVEN,GCP_PUBSUB` ✓
  * `project.registered` → `migration.job.created` → orchestrator picks up → workflow session created ✓
  * Agent pipeline **fails** — most recent symptom: `OptimisticLockException` on `workflow_sessions.version` (two threads race on save).
* **Last unfinished UI fix:** the `JobDetail` page was blank in the browser. Root cause was `sockjs-client` referencing Node's `global` in the browser → `ReferenceError: global is not defined` from `app.routes.ts:57` (lazy-loaded `JobDetailComponent`). A polyfill was added at `frontend/src/polyfills.ts` containing `(window as any).global = window;` and `angular.json` updated to point at it. **Not yet rebuilt/tested** — first thing to do after recovery: rebuild frontend, hard-reload browser, click "Open" on a job, watch the live pipeline graph render.

---

## 1. What is Altrix?

AI-powered migration platform that automatically converts Java code from **Google Cloud Pub/Sub → Apache Kafka** (extensible to other stacks). User uploads/imports a GitHub repo; AI agents analyse → plan → migrate → validate → report; user downloads the rewritten ZIP. Hexagonal Architecture (Ports & Adapters), DDD, CQRS, event-driven over Kafka.

---

## 2. Repository layout

```
altrix/
├── HANDOFF.md                    ← this file
├── CLAUDE.md                     ← project rules for AI assistants
├── backend/
│   ├── gradlew                   ← all Gradle commands run from here
│   ├── docker-compose.yml        ← Postgres, Redis, Kafka, MinIO, SonarQube, DefectDojo, Kafdrop
│   ├── .env                      ← all secrets / API keys / config (NOT committed)
│   ├── scripts/
│   │   ├── start-dev.sh          ← infra only (Docker)
│   │   ├── start-services.sh     ← 3 backend + frontend (background, logs in backend/logs)
│   │   ├── start-all.sh          ← infra + services
│   │   ├── stop-dev.sh           ← stop everything
│   │   └── analyze.sh            ← SonarQube + Gitleaks + Semgrep + Trivy → DefectDojo
│   ├── platform-common/          ← pure Java library, no Spring
│   ├── platform-project/         ← port 8082 — upload, GCP-PubSub detection, project registry
│   ├── platform-job/             ← port 8083 — job lifecycle, Redis status cache, ZIP download
│   └── platform-orchestrator/    ← port 8084 — auth, AI pipeline, WebSocket
└── frontend/                     ← Angular 18 standalone (port 4200, ng serve)
```

External sample project to migrate: `~/Documents/test/` (also pushed to GitHub).

---

## 3. Quick boot (after fresh OS / clean clone)

```bash
# Prerequisites: Java 21, Node 20+, Docker, ng (npm i -g @angular/cli@18)
git clone <repo> altrix && cd altrix

# 1. Restore .env (NOT in git — see § 8 for full contents)
cp /backup/.env backend/.env       # or recreate manually

# 2. Bring up everything
cd backend
./scripts/start-all.sh             # ≈ 90s
```

Then open http://localhost:4200, sign in with GitHub, and you're at the dashboard.

If anything is stuck:
```bash
./scripts/stop-dev.sh              # full teardown
docker compose down -v             # also drops volumes (clean DB)
./scripts/start-all.sh
```

---

## 4. Architecture (one paragraph each)

**Hexagonal / DDD**: every service has `domain/model`, `domain/port/in`, `domain/port/out`, `domain/service` (zero framework imports), and `adapter/in/{rest,kafka,websocket}`, `adapter/out/{persistence,messaging,storage}`, `infrastructure/config`. JPA entities are adapter-layer only; domain entities are plain Java.

**Event chain** (one Kafka hop per arrow):
```
platform-project  →[project.registered]→         platform-job
platform-job      →[migration.job.created]→       platform-orchestrator
platform-orchestrator →[migration.job.status.update]→ platform-job
platform-job      →[migration.job.completed]→     (no consumer yet)
```
Kafka message format is pipe-delimited plain text (`userId|storageKey`, `jobId|status|extra`). Services **never** call each other over REST except: orchestrator → platform-project's `/api/v1/projects/upload` for GitHub-ingested ZIPs.

**Agent pipeline** (orchestrator, LangGraph4j workflow). Each agent is a `MigrationAgent<I,O>` bean, executed in order by `MigrationWorkflowGraph`:

| Order | Agent | Tier | Input → Output |
|---|---|---|---|
| 1 | `ContextAnalyzerAgent` | ANALYSIS | `ProjectContext` → `AnalysisReport` |
| 2 | `MigrationPlannerAgent` | ANALYSIS | `AnalysisReport` → `MigrationPlan` |
| 3 | `TypedCoreMigratorAgent` | **MIGRATION** (heavy) | `ApprovedPlan` → `MigrationArtifact` |
| 4 | `SandboxValidatorAgent` | ANALYSIS | `MigrationArtifact` → `ValidationReport` |
| 5 | `ReportGeneratorAgent` | MIGRATION | `WorkflowOutcome` → `MigrationReport` |

**AI routing**: `ProviderRouter` walks an ordered chain — `TIER_PREFERENCE` strategy with `PAID_FIRST`. `ProviderRegistry` rebuilds atomically when a key changes. Circuit-breaker per provider via Resilience4j. Adapters: `GroqProviderFactory`, `OpenAiProviderFactory`, `AnthropicProviderFactory`, `DeepSeekProviderFactory`, `NvidiaProviderFactory`, `OllamaProviderFactory`, `OpenRouterProviderFactory`.

**Auth**: GitHub OAuth → `MultiProviderOAuth2UserService` dispatches via strategy pattern (`GitHubAuthStrategy`, `GitLabAuthStrategy` — GitLab is wired but **disabled** in `application.yml` placeholder defaults). Issues RS256 JWT with `sub` = internal user UUID. Refresh token rotation stored hashed in `refresh_tokens`. Generalized `users` + `user_auth_providers` tables (Flyway V14). On the frontend, `AuthService.userId` decodes the JWT `sub` synchronously so the X-User-Id header is set on the very first request after a page reload.

---

## 5. AI provider state (as of last session)

Set in `backend/.env`:
* **Groq** — `GROQ_API_KEY=gsk_…` ✓ (free, fast — fallback in the chain)
* **NVIDIA NIM** — `NVIDIA_API_KEY=nvapi-…` ✓ (paid; free trial credits)
  * `NVIDIA_MODEL_MIGRATION=nvidia/llama-3.3-nemotron-super-49b-v1`
* **OpenRouter** — `OPENROUTER_API_KEY=sk-or-v1-…` ✓ (paid gateway, using `:free` models for testing)
  * `OPENROUTER_MODEL_ANALYSIS=meta-llama/llama-3.3-70b-instruct:free`
  * `OPENROUTER_MODEL_MIGRATION=qwen/qwen-2.5-coder-32b-instruct:free` ← best free code model
* **OpenAI / Anthropic / DeepSeek / Ollama** — disabled, no keys

**Routing chain at boot:** NVIDIA → OpenRouter → Groq (PAID_FIRST).

**RAG embedding is intentionally disabled** (no OpenAI key, no Ollama locally). The `CodeIndexingAgent` and `PgVectorEmbeddingStoreAdapter` both catch `IllegalStateException` from the `DisabledEmbeddingModel` and log a warning — pipeline continues without semantic search context. If you want RAG back, either set `OPENAI_API_KEY` for `text-embedding-3-small` (1536-dim) or run Ollama locally and switch `rag.embedding.provider=ollama` (note: schema is `vector(1536)`, so model dims must match or you need a Flyway migration).

---

## 6. Current state — what works, what doesn't

### Works ✓
* Docker stack: Postgres + pgvector, Redis, Kafka 3.9, MinIO, Kafdrop, pgAdmin, SonarQube, DefectDojo, GCP Pub/Sub emulator.
* `start-all.sh` / `stop-dev.sh` / `analyze.sh` / `start-services.sh` — all idempotent, log to `backend/logs/`.
* GitHub OAuth login (one-click) → JWT stored in sessionStorage, refresh token in HttpOnly cookie.
* Projects page → "Import from GitHub" → repo selector populated from your account → import downloads ZIP via orchestrator → forwards multipart to platform-project → detection runs → project shows in "Your projects" with `eligibleForMigration` + `detectedTechnologies` columns.
* DELETE on Projects, Jobs, Sessions (trash button per row + confirm dialog). Ownership-enforced server-side.
* Kafka chain to job + orchestrator session creation.
* Theme toggle (dark / light) persisted via localStorage.
* Frontend pages: Dashboard, Projects, Jobs (with filter chips), Job Detail (header + Refresh + Download button when DONE), Sessions (admin actions: approve / reject / pause / resume), AI Providers (toggle + edit), Billing (token usage + per-agent breakdown).
* WebSocket / STOMP wiring (`/ws` SockJS endpoint, topic `/topic/jobs/{jobId}`) — backend publishes `ProgressEvent`s as agents fire.

### Doesn't work / pending ✗
1. **JobDetail page blank** — last attempted fix in progress: SockJS browser polyfill at `frontend/src/polyfills.ts`. **Not yet rebuilt** — next step is `cd frontend && npx ng build --configuration=development`, restart `ng serve`, hard-reload (`Ctrl+Shift+R`), then click Open on a job. Should render header + 5-stage animated pipeline graph + details card.
2. **Optimistic-lock collision on `workflow_sessions.version`** — last observed error: `Row was updated or deleted by another transaction`. Two writers race during the agent pipeline (probably the RAG-disabled warning path racing with the agent-progress writer). Need to serialise writes — either single transaction per session update or pessimistic lock.
3. **The actual migration of the sample project hasn't completed yet** — once the two blockers above are fixed, run a fresh import to see the full Analyse → Plan → Migrate → Validate → Report flow.
4. **GitLab auth** is wired (strategy pattern exists) but parked. `GITLAB_CLIENT_ID` defaults to a placeholder so Spring Boot's OAuth2 client validation passes at startup; actual login will fail until real credentials are set.
5. **No "trigger migration" button per project** — currently `project.registered` Kafka event fires automatically on upload and starts a job. Users can't re-trigger. Acceptable for now.
6. **Frontend builds with deprecation warnings** but they're harmless (Angular 18 features used in mostly v18-compatible ways).
7. **A few tests fail** in `platform-orchestrator` (pre-existing — `ProviderRegistryTest.throws_when_no_factory_is_enabled`). Confirmed pre-existing, not caused by recent changes.

---

## 7. Major bugs fixed in this session (so we don't trip on them again)

| Symptom | Root cause | Fix |
|---|---|---|
| Spring Boot wouldn't start with `gitlab` registration | Spring validates every `oauth2.client.registration.*` at boot, even unused ones | Sentinel non-empty defaults (`disabled-gitlab-client-id`) so validation passes |
| GitHub repo ZIP download → 404 | `RestTemplate` URL-encoded the `/` in `salaheddinemchich/test-altrix` to `%2F` | Split into `{owner}` and `{repo}` path variables |
| Project upload → 415 Unsupported Media Type | Two stacked bugs: (a) `RestTemplate` multipart didn't set boundary properly, (b) `@RequestPart` on the enum `ConfigFormatPreference` required `application/json` on the part | Switched orchestrator → platform-project call to Java 11 `HttpClient` with hand-built multipart bytes; changed receiver to `@RequestParam` |
| Workflow session insert → `column "migrated_files" is of type jsonb but expression is of type character varying` | Hibernate sends VARCHAR; Postgres won't implicitly cast to jsonb | Added `?stringtype=unspecified` to the JDBC URL — driver sends as `unspecified`, server coerces |
| Pipeline failed with `NotSerializableException: ProjectContext` (and 11 sibling types) | LangGraph4j checkpoints workflow state via Java serialization; common-domain records didn't implement `Serializable` | All 12 types in `platform-common/domain/model/` now `implements Serializable` |
| `Pipeline FAILED: RAG embedding is disabled` | `DisabledEmbeddingModel.embedAll` threw `IllegalStateException` | `CodeIndexingAgent.index()` and `PgVectorEmbeddingStoreAdapter.findRelevant()` both catch + log a warning; pipeline proceeds without semantic search |
| Frontend "Could not load jobs — HTTP 500" with `findAll.userId: must not be blank` | The X-User-Id interceptor read `AuthService.user()` which was null until `/auth/me` returned | `AuthService.userId` now decodes JWT `sub` synchronously; `UserService.currentUserId` reads from it |
| Frontend "0 undefined" on `/api/v1/projects/from-github` | Two orchestrator processes racing on port 8084 (from parallel restart attempts) | Always kill all listeners on the port before restart |
| Flyway migration failure: `ADD CONSTRAINT IF NOT EXISTS` not supported | PostgreSQL syntax error | Wrapped in `DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_constraint…) ALTER TABLE … ADD CONSTRAINT … END IF; END$$;` |
| Job Detail page blank | (LATEST — pending verification) Pure CSR was already set up but `sockjs-client` references Node's `global` → `ReferenceError` from lazy-loaded JobDetailComponent | Added `src/polyfills.ts` with `(window as any).global = window;` and `angular.json → polyfills: ["src/polyfills.ts"]` |

---

## 8. `backend/.env` — what needs to be in it after recovery

```bash
# ── Database (matches docker-compose) ─────────────────────────────────────
POSTGRES_USER=altrix
POSTGRES_PASSWORD=altrix_pass
POSTGRES_DB=altrix_db

# ── Redis / MinIO / Kafka — local defaults fine ──────────────────────────
REDIS_PASSWORD=redis_pass
MINIO_ROOT_USER=admin
MINIO_ROOT_PASSWORD=password123
PGADMIN_PASSWORD=admin

# ── Provider config encryption (AES-256-GCM, base64) ─────────────────────
# Generate fresh on recovery: openssl rand -base64 32
PROVIDER_CONFIG_ENCRYPTION_KEY=<base64-32-bytes>

# ── GitHub OAuth (regenerate at github.com/settings/developers) ──────────
GITHUB_CLIENT_ID=<your-github-oauth-app-id>
GITHUB_CLIENT_SECRET=<your-github-oauth-app-secret>

# ── AI providers (free tiers — regenerate if rotated) ────────────────────
GROQ_API_KEY=<gsk_…>
NVIDIA_ENABLED=true
NVIDIA_API_KEY=<nvapi-…>
NVIDIA_MODEL_ANALYSIS=meta/llama-3.1-8b-instruct
NVIDIA_MODEL_MIGRATION=nvidia/llama-3.3-nemotron-super-49b-v1
OPENROUTER_ENABLED=true
OPENROUTER_API_KEY=<sk-or-v1-…>
OPENROUTER_MODEL_ANALYSIS=meta-llama/llama-3.3-70b-instruct:free
OPENROUTER_MODEL_MIGRATION=qwen/qwen-2.5-coder-32b-instruct:free

# ── Routing strategy ─────────────────────────────────────────────────────
AI_ROUTING_STRATEGY=TIER_PREFERENCE
AI_ROUTING_TIER_PREFERENCE=PAID_FIRST

# ── DefectDojo admin (optional) ──────────────────────────────────────────
DD_ADMIN_PASSWORD=admin123
DD_DB_PASSWORD=defectdojo_pass
DD_SECRET_KEY=<random>
DD_AES_256_KEY=<random>

# ── SonarQube (optional) ─────────────────────────────────────────────────
SONAR_TOKEN=<generated at first run from http://localhost:9003/account/security>
SONAR_HOST_URL=http://localhost:9003
SONAR_PROJECT_KEY=altrix
```

**API keys** for NVIDIA and OpenRouter were issued in this session — they were:
* NVIDIA: `nvapi-swAfUil…` (full key was provided in the chat — regenerate at https://build.nvidia.com/ )
* OpenRouter: `sk-or-v1-b915c40fa…` (full key was provided — regenerate at https://openrouter.ai/keys )

If they haven't been rotated, the same keys still work. If you regenerate, just paste the new values into `.env` and run `./scripts/start-services.sh --only orchestrator` to pick them up (`.env` auto-loads via each module's `build.gradle.kts`).

---

## 9. Sample project to migrate

* GitHub: `salaheddinemchich/test-altrix` (Java, Maven, Jakarta EE 10).
* Local clone: `~/Documents/test/` (33 Java files, compiles cleanly via `mvn compile`).
* Contains the exact `PubsubServiceImpl` class the user provided — uses legacy GCP Pub/Sub REST v1 API (`com.google.api.services.pubsub.Pubsub`).
* Orders + Payments CRUD layered on top with JAX-RS REST endpoints and an `OrderEventListener` (EJB `@Singleton @Startup` polling Pub/Sub every 5s).
* Will be detected by Altrix as `eligibleForMigration: true` with technologies `[JAKARTA_EE, MAVEN, GCP_PUBSUB]`.

---

## 10. Frontend layout

| Route | Component | What it does |
|---|---|---|
| `/auth/login` | `LoginComponent` | Single GitHub button. Google/GitLab buttons were removed |
| `/auth/callback` | `AuthCallbackComponent` | Reads `?token=…` from URL, stores in sessionStorage, redirects to `/` |
| `/` | `HomeComponent` | Dashboard — counts (projects, jobs, sessions, providers), recent jobs |
| `/projects` | `ProjectsComponent` | GitHub repo picker + Your projects table with trash button |
| `/jobs` | `JobsComponent` | Filter chips + table; each row has Open button + trash button |
| `/jobs/:id` | `JobDetailComponent` | Header + **live pipeline graph** (5 stages, animated via STOMP/WebSocket) + details + session card |
| `/sessions` | `SessionsComponent` | List + admin actions (approve/reject/pause/resume) + trash button |
| `/providers` | `ProvidersComponent` | AI provider toggles & per-provider API key + model config |
| `/billing` | `BillingComponent` | Token usage summary + per-agent + per-provider cost tables |

Routing config: `frontend/src/app/app.routes.ts`. `provideRouter(routes, withComponentInputBinding())` enables `input.required<string>()` from URL params.

**Pure CSR — no SSR.** `app.config.ts` no longer calls `provideClientHydration()`; `angular.json` has `prerender: false, ssr: false`. The previous SSR setup pre-rendered `<app-login>` for protected routes (no token in storage server-side), causing hydration mismatches that left the page blank.

---

## 11. Database (Postgres `altrix_db`)

Three Flyway migration histories (one per service, separated by `flyway_schema_history_*` table):

| Table | Owner service | Notes |
|---|---|---|
| `users` | orchestrator | UUID PK, no provider fields; from V14 |
| `user_auth_providers` | orchestrator | Links user → (provider_type, provider_id, encrypted_access_token); UNIQUE(provider_type, provider_id) |
| `refresh_tokens` | orchestrator | SHA-256 hash, FK to users.id |
| `provider_configs` | orchestrator | User-overrides for AI providers, AES-encrypted API keys |
| `code_embeddings` | orchestrator | pgvector(1536) for RAG |
| `token_usage`, `ai_call_ledger` | orchestrator | Billing |
| `workflow_sessions` | orchestrator | JSONB `plan` and `migrated_files`; `version` for optimistic lock |
| `session_pause_history` | orchestrator | Audit trail |
| `projects` | platform-project | Includes `eligible_for_migration` (bool) + `detected_technologies` (CSV TEXT) since V4 |
| `migration_jobs` | platform-job | |

**JDBC trick:** the orchestrator's datasource URL has `?stringtype=unspecified` so JSONB columns accept VARCHAR binds. Without this, every workflow_sessions insert fails.

---

## 12. Day-1 recovery checklist

1. ☐ Install: Java 21, Node 20+, Docker, Angular CLI 18, Maven (for the sample project).
2. ☐ Clone the repo.
3. ☐ Recreate `backend/.env` (§ 8 above).
4. ☐ Regenerate GitHub OAuth app + paste IDs into `.env`.
5. ☐ Regenerate NVIDIA + OpenRouter keys if rotated.
6. ☐ `cd backend && ./scripts/start-all.sh` — verify all 4 endpoints respond 200 (`/actuator/health` for backends, `/` for frontend).
7. ☐ **First-time-after-recovery fix verification**: `cd frontend && npx ng build --configuration=development` and check no `ReferenceError: global`. Restart `ng serve`, hard-reload (`Ctrl+Shift+R`), sign in via GitHub, click "Open" on any job — the pipeline graph should render.
8. ☐ Tackle the optimistic-lock issue on `workflow_sessions.version` next.
9. ☐ Re-import `salaheddinemchich/test-altrix` and watch the agent pipeline run to completion (or surface the next bug).

---

## 13. Conversation continuity — for the next AI session

When opening a new chat, paste this:

> "Read `HANDOFF.md` at the repo root for full context. We're mid-development on Altrix — a Java code migrator that converts GCP Pub/Sub to Kafka via an AI agent pipeline. Latest pending item is the SockJS `global` polyfill (already written to `frontend/src/polyfills.ts`) — needs a frontend rebuild + browser hard-reload to verify the JobDetail page renders, then we tackle the optimistic-lock collision on workflow_sessions."

Everything you need to know to be useful within 5 minutes is in this file. If you can't find something here, check `CLAUDE.md` for the original project rules.
