package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All provider identity config (endpoints, model names, credentials).
 * Nothing is hardcoded in Java — every value comes from {@code application.yml}
 * or an environment variable override.
 *
 * <p>API key fields map to environment variables via Spring's
 * {@code ${ENV_VAR:default}} syntax in {@code application.yml}. They are
 * NEVER logged — only {@code [CONFIGURED]} / {@code [NOT SET]} markers are used.
 */
@ConfigurationProperties(prefix = "ai.providers")
public record AiProvidersConfig(
        OpenAiCompatibleConfig groq,
        OpenAiCompatibleConfig openai,
        OpenAiCompatibleConfig deepseek,
        OpenAiCompatibleConfig nvidia,
        OpenAiCompatibleConfig openrouter,
        AnthropicConfig anthropic,
        OllamaConfig ollama
) {

    /**
     * Shared config shape for any provider with an OpenAI-compatible REST API
     * (Groq, OpenAI, DeepSeek, Together.ai, Fireworks.ai, etc.).
     */
    public record OpenAiCompatibleConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String modelAnalysis,
            @DefaultValue("") String modelMigration,
            @DefaultValue("120") long timeoutSeconds,
            @DefaultValue("0.1") double temperature,
            // Output cap for the migration tier — must be generous because a
            // full pom.xml or large Java file easily exceeds the typical 1–4k
            // provider default and gets silently truncated mid-stream.
            @DefaultValue("16000") int maxTokens
    ) {
    }

    public record AnthropicConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String modelAnalysis,
            @DefaultValue("") String modelMigration,
            @DefaultValue("120") long timeoutSeconds,
            @DefaultValue("0.1") double temperature,
            @DefaultValue("16000") int maxTokens
    ) {
    }

    public record OllamaConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("llama3.2:3b") String modelAnalysis,
            @DefaultValue("llama3.1:8b") String modelMigration,
            @DefaultValue("300") long timeoutSeconds,
            @DefaultValue("false") boolean warmup,
            @DefaultValue("16000") int maxTokens
    ) {
    }
}
