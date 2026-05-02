package com.migrator.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ai.providers")
public record AiProvidersConfig(
        GroqConfig groq,
        OpenAiConfig openai,
        AnthropicConfig anthropic,
        OllamaConfig ollama
) {

    public record GroqConfig(
            @DefaultValue("true")  boolean enabled,
            @DefaultValue("")      String  apiKey,
            @DefaultValue("https://api.groq.com/openai/v1") String baseUrl,
            @DefaultValue("llama-3.1-8b-instant")           String modelAnalysis,
            @DefaultValue("llama-3.3-70b-versatile")        String modelMigration
    ) {}

    public record OpenAiConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("")      String  apiKey,
            @DefaultValue("gpt-4o-mini") String modelAnalysis,
            @DefaultValue("gpt-4o")      String modelMigration
    ) {}

    public record AnthropicConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("")      String  apiKey,
            @DefaultValue("claude-haiku-4-5-20251001") String modelAnalysis,
            @DefaultValue("claude-sonnet-4-6")         String modelMigration
    ) {}

    public record OllamaConfig(
            @DefaultValue("false")                    boolean enabled,
            @DefaultValue("http://localhost:11434")   String  baseUrl,
            @DefaultValue("llama3.2:3b")              String  modelAnalysis,
            @DefaultValue("llama3.1:8b")              String  modelMigration
    ) {}
}
