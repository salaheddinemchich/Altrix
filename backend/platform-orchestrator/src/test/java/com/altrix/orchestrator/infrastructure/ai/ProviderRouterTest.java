package com.altrix.orchestrator.infrastructure.ai;

import com.altrix.orchestrator.domain.port.out.AiCallLedgerPort;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;
import com.altrix.orchestrator.infrastructure.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infrastructure.ai.provider.ProviderTier;
import com.altrix.orchestrator.infrastructure.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infrastructure.config.AiRoutingConfig;
import com.altrix.orchestrator.infrastructure.config.AiRoutingConfig.*;
import com.altrix.orchestrator.infrastructure.config.McpConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ProviderRouterTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ChatLanguageModel modelReturning(String text) {
        ChatLanguageModel m = mock(ChatLanguageModel.class);
        when(m.generate(anyList())).thenReturn(Response.from(AiMessage.from(text)));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static ChatLanguageModel modelThrowing() {
        ChatLanguageModel m = mock(ChatLanguageModel.class);
        when(m.generate(anyList())).thenThrow(new RuntimeException("provider error"));
        return m;
    }

    private static RegisteredProvider provider(String id, ProviderCostTier costTier, ChatLanguageModel model) {
        return new RegisteredProvider(id, costTier, model, model);
    }

    private static ProviderRegistry registryOf(RegisteredProvider... providers) {
        ProviderRegistry r = mock(ProviderRegistry.class);
        when(r.all()).thenReturn(List.of(providers));
        return r;
    }

    private static AiRoutingConfig defaultRouting() {
        return new AiRoutingConfig(
                RoutingStrategy.TIER_PREFERENCE,
                TierPreference.PAID_FIRST,
                List.of(),
                new CircuitBreakerSettings(10, 50f, 30L, 3),
                new RetrySettings(1, 10L),   // maxAttempts=1 → no retry in unit tests
                new BulkheadSettings(10, 10, 5000L),
                0L
        );
    }

    /**
     * Builds a router with MCP disabled and a no-op token-usage sink.
     */
    private static ProviderRouter router(ProviderRegistry registry, AiRoutingConfig routing) {
        McpConfig mcpCfg = new McpConfig(false, 5, List.of());
        TokenUsagePort tokenUsage = mock(TokenUsagePort.class);
        AiCallLedgerPort ledger = mock(AiCallLedgerPort.class);
        return new ProviderRouter(registry, routing, mcpCfg, Optional.empty(), tokenUsage, ledger);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void returns_response_from_first_available_provider() {
        ProviderRouter router = router(
                registryOf(provider("openai", ProviderCostTier.PAID, modelReturning("hello"))),
                defaultRouting()
        );

        assertThat(router.chat(ProviderTier.MIGRATION, "sys", "usr")).isEqualTo("hello");
    }

    @Test
    void falls_back_when_first_provider_throws() {
        ChatLanguageModel broken = modelThrowing();
        ChatLanguageModel ok = modelReturning("fallback");

        ProviderRouter router = router(
                registryOf(
                        provider("openai", ProviderCostTier.PAID, broken),
                        provider("groq", ProviderCostTier.FREE, ok)
                ),
                defaultRouting()
        );

        assertThat(router.chat(ProviderTier.MIGRATION, "sys", "usr")).isEqualTo("fallback");
    }

    @Test
    void throws_AllProvidersUnavailable_when_every_provider_fails() {
        ProviderRouter router = router(
                registryOf(
                        provider("openai", ProviderCostTier.PAID, modelThrowing()),
                        provider("groq", ProviderCostTier.FREE, modelThrowing())
                ),
                defaultRouting()
        );

        assertThatThrownBy(() -> router.chat(ProviderTier.ANALYSIS, "sys", "usr"))
                .isInstanceOf(ProviderRouter.AllProvidersUnavailableException.class)
                .hasMessageContaining("ANALYSIS");
    }

    @Test
    void circuit_breaker_opens_after_repeated_failures() {
        ProviderRouter router = router(
                registryOf(provider("groq", ProviderCostTier.FREE, modelThrowing())),
                defaultRouting()
        );

        for (int i = 0; i < 10; i++) {
            try {
                router.chat(ProviderTier.MIGRATION, "s", "u");
            } catch (Exception ignored) {
            }
        }

        assertThat(router.circuitBreakerStates().get("groq"))
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    }

    @Test
    void analysis_tier_routes_to_analysis_model() {
        ChatLanguageModel analysisModel = modelReturning("analysis result");
        ChatLanguageModel migrationModel = modelReturning("migration result");
        RegisteredProvider p = new RegisteredProvider("groq", ProviderCostTier.FREE, analysisModel, migrationModel);

        ProviderRouter router = router(registryOf(p), defaultRouting());

        assertThat(router.chat(ProviderTier.ANALYSIS, "sys", "usr")).isEqualTo("analysis result");
        verify(analysisModel).generate(anyList());
        verifyNoInteractions(migrationModel);
    }

    @Test
    void explicit_order_strategy_respects_configured_order() {
        ChatLanguageModel groqModel = modelReturning("groq");
        ChatLanguageModel openaiModel = modelReturning("openai");

        AiRoutingConfig explicitCfg = new AiRoutingConfig(
                RoutingStrategy.EXPLICIT_ORDER,
                TierPreference.PAID_FIRST,
                List.of("groq", "openai"),
                new CircuitBreakerSettings(10, 50f, 30L, 3),
                new RetrySettings(1, 10L),
                new BulkheadSettings(10, 10, 5000L),
                0L
        );

        ProviderRouter r = router(
                registryOf(
                        provider("openai", ProviderCostTier.PAID, openaiModel),
                        provider("groq", ProviderCostTier.FREE, groqModel)
                ),
                explicitCfg
        );

        // Explicit order says groq first — cost tier is irrelevant here
        assertThat(r.chat(ProviderTier.MIGRATION, "s", "u")).isEqualTo("groq");
    }

    @Test
    void chatAgentic_falls_back_to_chat_when_mcp_disabled() {
        ChatLanguageModel model = modelReturning("agentic result");
        ProviderRouter router = router(
                registryOf(provider("groq", ProviderCostTier.FREE, model)),
                defaultRouting()
        );

        // MCP is disabled (Optional.empty) — should silently fall back to regular chat
        assertThat(router.chatAgentic(ProviderTier.MIGRATION, "sys", "usr")).isEqualTo("agentic result");
    }
}
