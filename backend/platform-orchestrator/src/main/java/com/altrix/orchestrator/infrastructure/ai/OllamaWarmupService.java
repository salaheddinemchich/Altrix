package com.altrix.orchestrator.infrastructure.ai;

import com.altrix.orchestrator.infrastructure.ai.provider.ProviderTier;
import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Fires two cheap "hello" calls against Ollama on startup so the JVM-level
 * model loading cost is paid once, not on the first real user request (#171).
 * <p>
 * Activated only when {@code ai.providers.ollama.warmup=true} AND Ollama is
 * enabled. Runs asynchronously so it never delays application readiness.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.providers.ollama", name = "warmup", havingValue = "true")
public class OllamaWarmupService {

    private final AiProvidersConfig aiProvidersConfig;
    private final ProviderRouter providerRouter;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        AiProvidersConfig.OllamaConfig ollama = aiProvidersConfig.ollama();
        if (!ollama.enabled()) {
            log.debug("Ollama warmup skipped — provider not enabled");
            return;
        }
        log.info("Warming up Ollama models ({} / {}) — first-call latency will be paid now",
                ollama.modelAnalysis(), ollama.modelMigration());
        try {
            providerRouter.chat(ProviderTier.ANALYSIS, "You are a helpful assistant.", "Hello");
            providerRouter.chat(ProviderTier.MIGRATION, "You are a helpful assistant.", "Hello");
            log.info("Ollama warmup completed successfully");
        } catch (Exception e) {
            log.warn("Ollama warmup failed (non-fatal, first real call will load models): {}",
                    e.getMessage());
        }
    }
}
