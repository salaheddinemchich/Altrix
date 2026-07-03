package com.altrix.orchestrator.infrastructure.ai.provider;

/**
 * Immutable merged config for a single provider.
 * Produced by {@link ProviderConfigResolver} — DB override wins over YAML default.
 * Factories use this to build {@link RegisteredProvider} instances.
 */
public record ResolvedProviderConfig(
        boolean enabled,
        String apiKey,          // decrypted; empty string for key-less providers (Ollama)
        String baseUrl,         // empty string when not applicable (Anthropic)
        String modelAnalysis,
        String modelMigration,
        double temperature,
        long timeoutSeconds,
        // Output cap — provider defaults are often only 1–4k tokens, which
        // silently truncates large file rewrites mid-stream.  See AiProvidersConfig.
        int maxTokens
) {
    /**
     * True when enabled AND (has an API key OR is a local/key-less provider).
     */
    public boolean isEffectivelyEnabled(boolean requiresApiKey) {
        return enabled && (!requiresApiKey || !apiKey.isBlank());
    }
}
