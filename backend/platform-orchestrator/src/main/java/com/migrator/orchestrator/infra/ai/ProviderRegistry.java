package com.migrator.orchestrator.infra.ai;

import com.migrator.orchestrator.infrastructure.config.AiProvidersConfig;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds and exposes an ordered, immutable list of enabled providers.
 * Priority: paid (OpenAI, Anthropic) before free (Groq, Ollama) — paid
 * providers are tried first by ProviderRouter.
 */
@Slf4j
@Component
public class ProviderRegistry {

    private final List<RegisteredProvider> providers;

    public ProviderRegistry(AiProvidersConfig cfg) {
        List<RegisteredProvider> list = new ArrayList<>();

        if (cfg.openai() != null && cfg.openai().enabled() && !cfg.openai().apiKey().isBlank()) {
            list.add(openAiProvider(cfg.openai()));
            log.info("AI provider registered: openai (paid)");
        }
        if (cfg.anthropic() != null && cfg.anthropic().enabled() && !cfg.anthropic().apiKey().isBlank()) {
            list.add(anthropicProvider(cfg.anthropic()));
            log.info("AI provider registered: anthropic (paid)");
        }
        if (cfg.groq() != null && cfg.groq().enabled() && !cfg.groq().apiKey().isBlank()) {
            list.add(groqProvider(cfg.groq()));
            log.info("AI provider registered: groq (free-tier)");
        }
        if (cfg.ollama() != null && cfg.ollama().enabled()) {
            list.add(ollamaProvider(cfg.ollama()));
            log.info("AI provider registered: ollama (local/free)");
        }

        if (list.isEmpty()) {
            throw new IllegalStateException(
                    "No AI provider is enabled. Set at least one under ai.providers.* in application.yml.");
        }

        this.providers = Collections.unmodifiableList(list);
        log.info("ProviderRegistry ready — {} provider(s) active", providers.size());
    }

    public List<RegisteredProvider> all() {
        return providers;
    }

    // ── provider factories ────────────────────────────────────────────────────

    private RegisteredProvider openAiProvider(AiProvidersConfig.OpenAiConfig c) {
        return new RegisteredProvider(
                "openai", false,
                openAiModel(null, c.apiKey(), c.modelAnalysis()),
                openAiModel(null, c.apiKey(), c.modelMigration())
        );
    }

    private RegisteredProvider anthropicProvider(AiProvidersConfig.AnthropicConfig c) {
        return new RegisteredProvider(
                "anthropic", false,
                anthropicModel(c.apiKey(), c.modelAnalysis()),
                anthropicModel(c.apiKey(), c.modelMigration())
        );
    }

    private RegisteredProvider groqProvider(AiProvidersConfig.GroqConfig c) {
        return new RegisteredProvider(
                "groq", true,
                openAiModel(c.baseUrl(), c.apiKey(), c.modelAnalysis()),
                openAiModel(c.baseUrl(), c.apiKey(), c.modelMigration())
        );
    }

    private RegisteredProvider ollamaProvider(AiProvidersConfig.OllamaConfig c) {
        return new RegisteredProvider(
                "ollama", true,
                ollamaModel(c.baseUrl(), c.modelAnalysis()),
                ollamaModel(c.baseUrl(), c.modelMigration())
        );
    }

    // ── LangChain4j model builders ────────────────────────────────────────────

    private ChatLanguageModel openAiModel(String baseUrl, String apiKey, String model) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(120));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private ChatLanguageModel anthropicModel(String apiKey, String model) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private ChatLanguageModel ollamaModel(String baseUrl, String model) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(300))
                .build();
    }
}
