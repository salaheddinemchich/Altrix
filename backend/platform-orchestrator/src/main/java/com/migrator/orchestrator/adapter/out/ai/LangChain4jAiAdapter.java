package com.migrator.orchestrator.adapter.out.ai;

import com.migrator.orchestrator.domain.port.out.AiPort;
import com.migrator.orchestrator.infra.ai.ProviderRouter;
import com.migrator.orchestrator.infra.ai.provider.ProviderTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Replaces the old single-provider GroqAiAdapter.
 *
 * <p>Maps the existing AiPort contract onto ProviderRouter:
 * <ul>
 *   <li>{@link #chatFast} → {@link ProviderTier#ANALYSIS} (cheap/small model)</li>
 *   <li>{@link #chat}     → {@link ProviderTier#MIGRATION} (powerful model)</li>
 * </ul>
 *
 * <p>All circuit-breaker logic and provider fallback lives inside ProviderRouter.
 * Agents and orchestrator remain unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jAiAdapter implements AiPort {

    private final ProviderRouter router;

    @Override
    public String chat(String systemPrompt, String userContent) {
        log.debug("AiPort.chat — routing to MIGRATION tier");
        return router.chat(ProviderTier.MIGRATION, systemPrompt, userContent);
    }

    @Override
    public String chatFast(String systemPrompt, String userContent) {
        log.debug("AiPort.chatFast — routing to ANALYSIS tier");
        return router.chat(ProviderTier.ANALYSIS, systemPrompt, userContent);
    }
}
