package com.altrix.orchestrator.infrastructure.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Configures the embedding model used by the RAG pipeline.
 *
 * <p>Two profiles are supported via {@code rag.embedding.provider}:
 * <ul>
 *   <li>{@code openai} (default) — uses text-embedding-3-small via OpenAI API (1536 dims)</li>
 *   <li>{@code ollama} — uses nomic-embed-text via local Ollama (768 dims — update vector(768) in V2 if used)</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RagConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "openai", matchIfMissing = true)
    public EmbeddingModel openAiEmbeddingModel(
            @Value("${rag.embedding.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${rag.embedding.openai.model:text-embedding-3-small}") String model
    ) {
        if (apiKey.isBlank()) {
            log.warn("RAG: rag.embedding.openai.api-key is not set — embedding calls will fail at runtime. "
                    + "Set OPENAI_API_KEY or rag.embedding.openai.api-key, "
                    + "or switch to rag.embedding.provider=ollama for a local model.");
        }
        log.info("RAG embedding model: OpenAI {}", model);
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "ollama")
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${rag.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${rag.embedding.ollama.model:nomic-embed-text}") String model
    ) {
        log.info("RAG embedding model: Ollama {} at {}", model, baseUrl);
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
