# AI Provider Architecture

This document explains the multi-provider AI routing system and MCP (Model Context Protocol) integration in the `platform-orchestrator` service.

---

## Table of Contents

1. [Architecture overview](#1-architecture-overview)
2. [Provider model](#2-provider-model)
3. [User-configurable providers (runtime overrides)](#3-user-configurable-providers-runtime-overrides)
4. [Routing strategies](#4-routing-strategies)
5. [Resilience: circuit breakers and retry](#5-resilience-circuit-breakers-and-retry)
6. [MCP — Model Context Protocol](#6-mcp--model-context-protocol)
7. [Security hardening](#7-security-hardening)
8. [How to add a new provider](#8-how-to-add-a-new-provider)
9. [Configuration reference](#9-configuration-reference)

---

## 1. Architecture overview

The AI subsystem follows the same **hexagonal (ports-and-adapters)** architecture as the rest of the service:

```
┌──────────────────────────────────────────────────────┐
│                    Domain layer                      │
│  ProviderConfig (aggregate root)                     │
│  ProviderConfigService (UpdateProviderConfigUseCase) │
│  ports/out: ProviderConfigRepository                 │
│             ApiKeyEncryptionPort                     │
│             ProviderRefreshPort                      │
└─────────────────────┬────────────────────────────────┘
                      │ depends on ↓ (inward)
┌──────────────────────────────────────────────────────┐
│               Infrastructure layer                   │
│  ProviderRegistry  ← ProviderFactory (×5)            │
│  ProviderRouter    ← ProviderSelectionStrategy       │
│  ProviderConfigResolver (DB override + YAML merge)   │
│  McpClientAdapter  (MCP JSON-RPC 2.0 client)         │
└──────────────────────────────────────────────────────┘
                      │ adapters ↓
┌──────────────────────────────────────────────────────┐
│                  Adapter layer                       │
│  ProviderConfigController (REST  GET/PUT /api/ai/…)  │
│  ProviderConfigPersistenceAdapter (JPA)              │
│  AesGcmEncryptionAdapter (AES-256-GCM)               │
└──────────────────────────────────────────────────────┘
```

**Key rules:**
- The domain never imports framework classes (Spring, LangChain4j, Hibernate).
- Every provider-specific detail lives in its own `ProviderFactory` implementation.
- `ProviderRegistry` and `ProviderRouter` contain **zero** provider-specific code — adding a provider requires only a new `@Component`.

---

## 2. Provider model

### ProviderCostTier vs ProviderTier

Two independent enums prevent naming confusion:

| Enum | Values | Meaning |
|------|--------|---------|
| `ProviderCostTier` | `FREE`, `PAID` | How the provider charges (billing classification) |
| `ProviderTier` | `ANALYSIS`, `MIGRATION` | Which call quality is needed for the current agent step |

`ANALYSIS` tier → fast/cheap model (e.g. `llama-3.1-8b-instant`).  
`MIGRATION` tier → powerful model (e.g. `llama-3.3-70b-versatile`, `claude-sonnet-4-6`).

Each `RegisteredProvider` record holds two `ChatLanguageModel` instances:

```java
RegisteredProvider(
    String id,                    // "groq", "openai", …
    ProviderCostTier costTier,    // FREE or PAID
    ChatLanguageModel analysisModel,
    ChatLanguageModel migrationModel
)
```

`provider.modelFor(ProviderTier.ANALYSIS)` picks the right model automatically.

### Factory Method pattern (Open-Closed Principle)

Each of the five providers has a `@Component` factory:

```
GroqProviderFactory       → FREE,  OpenAI-compatible
DeepSeekProviderFactory   → FREE,  OpenAI-compatible
OllamaProviderFactory     → FREE,  local (no API key)
OpenAiProviderFactory     → PAID,  OpenAI
AnthropicProviderFactory  → PAID,  Anthropic SDK
```

`ProviderRegistry` just calls `factory.isEnabled()` / `factory.build()` — no switch/case, no `if (name.equals("groq"))`.

---

## 3. User-configurable providers (runtime overrides)

### What users can change (without restarting the server)

Via `PUT /api/ai/providers/{providerId}`:

| Field | Effect |
|-------|--------|
| `enabled` | Toggle a provider on or off |
| `apiKey` | Supply a personal API key (write-only — never returned) |
| `baseUrl` | Point to a different or proxy endpoint |
| `modelAnalysis` | Override the analysis model name |
| `modelMigration` | Override the migration model name |

### Merge strategy

`ProviderConfigResolver` applies a **DB wins, YAML fallback** policy:

```
DB override (provider_configs table)
      ↓  (present and non-blank)
YAML default (application.yml / env vars)
```

This means:
- A user can set their own Groq API key without touching environment variables.
- Removing the DB row instantly reverts to the system key.

### API key encryption

Keys at rest are encrypted with **AES-256-GCM** (`AesGcmEncryptionAdapter`):
- A fresh random 12-byte IV is generated per `encrypt()` call.
- The stored format is `Base64(IV):Base64(ciphertext+authTag)`.
- The encryption key is a 32-byte secret loaded from `PROVIDER_CONFIG_ENCRYPTION_KEY` (env var).
- Keys are **never logged** — only `[CONFIGURED]` / `[NOT SET]` markers appear in logs.

Generate an encryption key:
```bash
openssl rand -base64 32
```

### Live reload

After saving a DB override, the service calls `ProviderRefreshPort.refreshProviders()`, which triggers `ProviderRegistry` to rebuild its `AtomicReference<List<RegisteredProvider>>`. All subsequent calls to `ProviderRouter` pick up the new config immediately — no restart required.

---

## 4. Routing strategies

Configured via `ai.routing.strategy` (default: `TIER_PREFERENCE`).

### TIER_PREFERENCE

Orders providers by `ProviderCostTier` according to `ai.routing.tier-preference`:

| Value | Order |
|-------|-------|
| `PAID_FIRST` (default) | PAID → FREE (quality first) |
| `FREE_FIRST` | FREE → PAID (cost first) |

Within the same tier, order is determined by the order factories were registered (Spring bean list order).

### EXPLICIT_ORDER

Set `ai.routing.explicit-order: [openai, anthropic, groq]` to control the exact sequence regardless of tier.

### Fallback chain

`ProviderRouter.chat()` iterates the ordered list. On failure or open circuit breaker, it moves to the next provider. If the list is exhausted, `AllProvidersUnavailableException` is thrown.

---

## 5. Resilience: circuit breakers and retry

### Circuit breakers

A `CircuitBreakerRegistry` is used instead of a static `Map<String, CircuitBreaker>`. This means:
- Circuit breakers are **created lazily** on first use.
- New providers added via runtime refresh automatically get a CB — no code change needed.
- All CBs share the same policy (configured centrally in `AiRoutingConfig`).

Default policy:

| Parameter | Default | Override |
|-----------|---------|----------|
| Sliding window | 10 calls | `AI_CB_WINDOW` |
| Failure rate threshold | 50% | `AI_CB_FAILURE_RATE` |
| Wait in OPEN state | 30 s | `AI_CB_WAIT_SECONDS` |
| Calls in HALF_OPEN | 3 | `AI_CB_HALF_OPEN_CALLS` |

### Retry

Retry is configured via `ai.routing.retry` and applied by the agent layer (not inside `ProviderRouter` to avoid masking CB state).

### Health endpoint

`GET /actuator/health` shows the circuit breaker states. `ProviderRouter.circuitBreakerStates()` can also be wired into a custom health indicator.

---

## 6. MCP — Model Context Protocol

### What is MCP?

The **Model Context Protocol** (MCP, spec 2024-11-05) is an open standard that lets AI models call **external tools** during a conversation. Instead of producing a single text response, the model can request tool executions (e.g. "search for X", "read file Y") and incorporate the results into its final answer.

MCP uses **JSON-RPC 2.0** over HTTP. This service implements the **Streamable HTTP transport**: a single POST endpoint per server that accepts JSON-RPC requests and returns JSON (or SSE-framed JSON).

### Architecture

```
ProviderRouter.chatAgentic()
       │
       ├─ sends messages + ToolSpecifications to model
       │
       │  ← model responds with ToolExecutionRequests
       │
       ├─ McpClientAdapter.executeTool(name, argumentsJson)
       │        │
       │        └─ POST tools/call → MCP server → result text
       │
       └─ appends ToolExecutionResultMessage, loops until plain response
```

### Activation

Enable MCP in `application.yml` (or environment variables):

```yaml
ai:
  mcp:
    enabled: true
    max-tool-iterations: 5      # max model↔tool round-trips per call
    servers:
      - name: filesystem
        base-url: http://localhost:3001/mcp
        timeout-seconds: 30
      - name: git
        base-url: http://localhost:3002/mcp
        timeout-seconds: 15
```

Set `AI_MCP_ENABLED=true` in production.

### Protocol flow

When `McpClientAdapter` initialises (Spring `@PostConstruct`), for each configured server it:

1. **POST `initialize`** — introduces the client and negotiates capabilities.
2. **POST `notifications/initialized`** — fire-and-forget handshake completion.
3. **POST `tools/list`** — fetches available tool specifications and caches them.

At inference time:

4. **`ProviderRouter.chatAgentic()`** passes cached `ToolSpecification` list to `ChatLanguageModel.generate(messages, tools)`.
5. If the model returns `ToolExecutionRequest` entries, for each:
   - **POST `tools/call`** with `{name, arguments}` to the server that registered that tool.
   - Append `ToolExecutionResultMessage` to the conversation.
6. The loop continues until the model returns a plain text response or `max-tool-iterations` is reached.

### Wire format examples

**tools/list request:**
```json
{ "jsonrpc": "2.0", "method": "tools/list", "params": {}, "id": 2 }
```

**tools/list response:**
```json
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "tools": [
      {
        "name": "search",
        "description": "Search for information on a topic",
        "inputSchema": {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Search query" }
          },
          "required": ["query"]
        }
      }
    ]
  }
}
```

**tools/call request:**
```json
{
  "jsonrpc": "2.0", "method": "tools/call",
  "params": { "name": "search", "arguments": { "query": "Kafka migration patterns" } },
  "id": 7
}
```

**tools/call response:**
```json
{
  "jsonrpc": "2.0", "id": 7,
  "result": {
    "content": [
      { "type": "text", "text": "Kafka migration typically involves..." }
    ]
  }
}
```

### Using chatAgentic in agents

Agents that need tool access call `ProviderRouter.chatAgentic()` instead of `chat()`:

```java
// In an agent that needs to query external context before migrating code
String result = providerRouter.chatAgentic(
    ProviderTier.MIGRATION,
    SYSTEM_PROMPT,
    "Migrate this Kafka consumer: " + sourceCode
);
```

If MCP is disabled, `chatAgentic` falls back to `chat` automatically — no `if (mcpEnabled)` guards needed in the agent.

### Graceful degradation

- `McpClientAdapter` is only registered as a Spring bean when `ai.mcp.enabled=true` (via `@ConditionalOnProperty`).
- `ProviderRouter` injects `Optional<McpToolsPort>` — it is `null` when MCP is off.
- Tool call failures return an error string to the model rather than throwing; the model can then respond without the tool's result.
- MCP server initialisation failures at startup are logged as warnings, not errors — the application starts normally with those servers skipped.

### Tool name collisions

If two MCP servers expose a tool with the same name, the last-registered server wins and a warning is logged. Name your tools with server-specific prefixes (e.g. `filesystem.read_file`, `git.log`) to avoid collisions.

### Compatible MCP servers

Any server implementing the MCP spec with Streamable HTTP transport works. Popular options:
- [@modelcontextprotocol/server-filesystem](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem)
- [@modelcontextprotocol/server-git](https://github.com/modelcontextprotocol/servers/tree/main/src/git)
- [@modelcontextprotocol/server-postgres](https://github.com/modelcontextprotocol/servers/tree/main/src/postgres)
- Any custom server built with the MCP SDK (TypeScript, Python, Java, Kotlin)

---

## 7. Security hardening

| Threat | Mitigation |
|--------|-----------|
| API key leakage in logs | Keys never logged; only `[CONFIGURED]` / `[NOT SET]` markers |
| API key leakage via REST | `GET /api/ai/providers` omits the `apiKey` field; it is write-only |
| Key storage (database) | AES-256-GCM with random IV per write; separate secret key via env var |
| Provider hammering on outage | Circuit breakers halt calls to failing providers for 30 s (configurable) |
| Runaway agentic loops | `max-tool-iterations` cap (default 5); loop throws on limit breach |
| MCP tool injection | Tools are validated against the cached list; unknown names are rejected without a network call |
| Raw HTTP error bodies in responses | `ProviderCallException` wraps all provider errors before propagating |

---

## 8. How to add a new provider

1. Add config fields to `AiProvidersConfig` (e.g. a new `record MyProviderConfig(...)`).
2. Add the YAML block to `application.yml` with env-var placeholders.
3. Create `MyProviderFactory implements ProviderFactory` annotated with `@Component`.
   - Implement `providerId()`, `costTier()`, `isEnabled()`, `build()`.
   - Use `ProviderConfigResolver` to merge DB overrides.
4. Done. `ProviderRegistry` auto-discovers the new factory. No changes to routing, circuit breaking, or any other class.

---

## 9. Configuration reference

### Provider config (`ai.providers.*`)

| Key | Default | Env var |
|-----|---------|---------|
| `ai.providers.groq.enabled` | `true` | `GROQ_ENABLED` |
| `ai.providers.groq.api-key` | — | `GROQ_API_KEY` |
| `ai.providers.groq.model-analysis` | `llama-3.1-8b-instant` | `GROQ_MODEL_ANALYSIS` |
| `ai.providers.groq.model-migration` | `llama-3.3-70b-versatile` | `GROQ_MODEL_MIGRATION` |
| `ai.providers.anthropic.enabled` | `false` | `ANTHROPIC_ENABLED` |
| `ai.providers.anthropic.api-key` | — | `ANTHROPIC_API_KEY` |
| `ai.providers.anthropic.model-analysis` | `claude-haiku-4-5-20251001` | `ANTHROPIC_MODEL_ANALYSIS` |
| `ai.providers.anthropic.model-migration` | `claude-sonnet-4-6` | `ANTHROPIC_MODEL_MIGRATION` |
| *(deepseek, ollama, openai follow the same pattern)* | | |

### Routing config (`ai.routing.*`)

| Key | Default | Env var |
|-----|---------|---------|
| `ai.routing.strategy` | `TIER_PREFERENCE` | `AI_ROUTING_STRATEGY` |
| `ai.routing.tier-preference` | `PAID_FIRST` | `AI_ROUTING_TIER_PREFERENCE` |
| `ai.routing.explicit-order` | *(empty)* | `AI_ROUTING_EXPLICIT_ORDER` |
| `ai.routing.circuit-breaker.sliding-window-size` | `10` | `AI_CB_WINDOW` |
| `ai.routing.circuit-breaker.failure-rate-threshold` | `50` | `AI_CB_FAILURE_RATE` |
| `ai.routing.circuit-breaker.wait-duration-open-seconds` | `30` | `AI_CB_WAIT_SECONDS` |
| `ai.routing.retry.max-attempts` | `2` | `AI_RETRY_MAX` |

### MCP config (`ai.mcp.*`)

| Key | Default | Env var |
|-----|---------|---------|
| `ai.mcp.enabled` | `false` | `AI_MCP_ENABLED` |
| `ai.mcp.max-tool-iterations` | `5` | `AI_MCP_MAX_TOOL_ITERATIONS` |
| `ai.mcp.servers[].name` | *(required)* | — |
| `ai.mcp.servers[].base-url` | *(required)* | — |
| `ai.mcp.servers[].timeout-seconds` | `30` | — |

### Encryption config

| Key | Default | Env var |
|-----|---------|---------|
| `encryption.provider-config.key` | *(empty — encryption disabled)* | `PROVIDER_CONFIG_ENCRYPTION_KEY` |

Generate with: `openssl rand -base64 32`
