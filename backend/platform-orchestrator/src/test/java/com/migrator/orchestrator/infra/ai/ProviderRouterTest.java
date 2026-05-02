package com.migrator.orchestrator.infra.ai;

import com.migrator.orchestrator.infra.ai.provider.ProviderTier;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ProviderRouterTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ChatLanguageModel modelReturning(String text) {
        ChatLanguageModel m = mock(ChatLanguageModel.class);
        when(m.generate(anyList())).thenReturn(
                Response.from(AiMessage.from(text))
        );
        return m;
    }

    @SuppressWarnings("unchecked")
    private static ChatLanguageModel modelThrowing(String msg) {
        ChatLanguageModel m = mock(ChatLanguageModel.class);
        when(m.generate(anyList())).thenThrow(new RuntimeException(msg));
        return m;
    }

    private static ProviderRegistry registryOf(RegisteredProvider... providers) {
        ProviderRegistry registry = mock(ProviderRegistry.class);
        when(registry.all()).thenReturn(List.of(providers));
        return registry;
    }

    private static RegisteredProvider provider(String id, ChatLanguageModel model) {
        return new RegisteredProvider(id, true, model, model);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void uses_first_provider_when_available() {
        ChatLanguageModel model = modelReturning("hello");
        ProviderRouter router   = new ProviderRouter(registryOf(provider("groq", model)));

        String result = router.chat(ProviderTier.MIGRATION, "sys", "user");

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void falls_back_to_second_provider_when_first_fails() {
        ChatLanguageModel broken = modelThrowing("rate limited");
        ChatLanguageModel ok     = modelReturning("fallback response");
        ProviderRouter router    = new ProviderRouter(registryOf(
                provider("openai", broken),
                provider("groq",   ok)
        ));

        String result = router.chat(ProviderTier.MIGRATION, "sys", "user");

        assertThat(result).isEqualTo("fallback response");
    }

    @Test
    void throws_when_all_providers_fail() {
        ChatLanguageModel broken = modelThrowing("network error");
        ProviderRouter router    = new ProviderRouter(registryOf(
                provider("openai", broken),
                provider("groq",   broken)
        ));

        assertThatThrownBy(() -> router.chat(ProviderTier.ANALYSIS, "sys", "user"))
                .isInstanceOf(ProviderRouter.AllProvidersUnavailableException.class)
                .hasMessageContaining("ANALYSIS");
    }

    @Test
    void circuit_breaker_opens_after_repeated_failures() {
        ChatLanguageModel model = modelThrowing("500 error");
        ProviderRouter router   = new ProviderRouter(registryOf(provider("groq", model)));

        // Drive the CB to open (need >50% failure in a window of 10)
        for (int i = 0; i < 10; i++) {
            try {
                router.chat(ProviderTier.MIGRATION, "sys", "user");
            } catch (Exception ignored) {}
        }

        assertThat(router.circuitBreakerStates().get("groq"))
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    }

    @Test
    void analysis_tier_uses_analysis_model() {
        ChatLanguageModel analysisModel  = modelReturning("analysis result");
        ChatLanguageModel migrationModel = modelReturning("migration result");
        RegisteredProvider p = new RegisteredProvider("groq", true, analysisModel, migrationModel);
        ProviderRouter router = new ProviderRouter(registryOf(p));

        String result = router.chat(ProviderTier.ANALYSIS, "sys", "user");

        assertThat(result).isEqualTo("analysis result");
        verify(analysisModel).generate(anyList());
        verifyNoInteractions(migrationModel);
    }
}
