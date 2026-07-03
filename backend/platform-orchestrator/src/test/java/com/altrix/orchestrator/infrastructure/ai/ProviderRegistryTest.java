package com.altrix.orchestrator.infrastructure.ai;

import com.altrix.orchestrator.infrastructure.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infrastructure.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infrastructure.ai.provider.factory.ProviderFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProviderRegistryTest {

    private static ProviderFactory enabledFactory(String id) {
        ProviderFactory f = mock(ProviderFactory.class);
        when(f.isEnabled()).thenReturn(true);
        when(f.providerId()).thenReturn(id);
        when(f.build()).thenReturn(
                new RegisteredProvider(id, ProviderCostTier.FREE, null, null));
        return f;
    }

    private static ProviderFactory disabledFactory(String id) {
        ProviderFactory f = mock(ProviderFactory.class);
        when(f.isEnabled()).thenReturn(false);
        when(f.providerId()).thenReturn(id);
        return f;
    }

    @Test
    void registers_only_enabled_factories() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                enabledFactory("groq"),
                disabledFactory("openai")
        ));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0).id()).isEqualTo("groq");
    }

    @Test
    void throws_when_no_factory_is_enabled() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(disabledFactory("groq"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AI provider is enabled");
    }

    @Test
    void registers_multiple_enabled_providers_in_order() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                enabledFactory("openai"),
                enabledFactory("groq"),
                enabledFactory("deepseek")
        ));

        assertThat(registry.all()).extracting(RegisteredProvider::id)
                .containsExactly("openai", "groq", "deepseek");
    }

    @Test
    void build_not_called_on_disabled_factory() {
        ProviderFactory disabled = disabledFactory("anthropic");
        ProviderFactory enabled = enabledFactory("groq");

        new ProviderRegistry(List.of(disabled, enabled));

        verify(disabled, never()).build();
        verify(enabled).build();
    }
}
