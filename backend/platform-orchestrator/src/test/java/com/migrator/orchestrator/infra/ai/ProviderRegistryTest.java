package com.migrator.orchestrator.infra.ai;

import com.migrator.orchestrator.infrastructure.config.AiProvidersConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryTest {

    private static AiProvidersConfig configWithGroqOnly(boolean enabled, String apiKey) {
        return new AiProvidersConfig(
                new AiProvidersConfig.GroqConfig(enabled, apiKey,
                        "https://api.groq.com/openai/v1",
                        "llama-3.1-8b-instant", "llama-3.3-70b-versatile"),
                new AiProvidersConfig.OpenAiConfig(false, "", "gpt-4o-mini", "gpt-4o"),
                new AiProvidersConfig.AnthropicConfig(false, "",
                        "claude-haiku-4-5-20251001", "claude-sonnet-4-6"),
                new AiProvidersConfig.OllamaConfig(false, "http://localhost:11434",
                        "llama3.2:3b", "llama3.1:8b")
        );
    }

    @Test
    void registers_groq_when_enabled_with_key() {
        ProviderRegistry registry = new ProviderRegistry(configWithGroqOnly(true, "gsk_test"));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0).id()).isEqualTo("groq");
        assertThat(registry.all().get(0).free()).isTrue();
    }

    @Test
    void throws_when_no_provider_enabled() {
        AiProvidersConfig cfg = configWithGroqOnly(false, "");

        assertThatThrownBy(() -> new ProviderRegistry(cfg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AI provider is enabled");
    }

    @Test
    void skips_groq_when_api_key_blank() {
        assertThatThrownBy(() -> new ProviderRegistry(configWithGroqOnly(true, "")))
                .isInstanceOf(IllegalStateException.class);
    }
}
