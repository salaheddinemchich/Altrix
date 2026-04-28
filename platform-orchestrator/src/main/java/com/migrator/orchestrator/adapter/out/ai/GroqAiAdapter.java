package com.migrator.orchestrator.adapter.out.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrator.orchestrator.domain.port.out.AiPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Groq AI adapter with dual-model support:
 * - Fast model (llama-3.1-8b-instant) for analysis agents — fewer tokens
 * - Main model (llama-3.3-70b-versatile) for migration agents — better quality
 */
@Slf4j
@Component
public class GroqAiAdapter implements AiPort {

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;
    private final String       modelFast;
    private final String       modelMain;

    public GroqAiAdapter(
            @Value("${ai.provider.base-url}") String baseUrl,
            @Value("${ai.provider.api-key}")  String apiKey,
            @Value("${ai.provider.model-fast:llama-3.1-8b-instant}") String modelFast,
            @Value("${ai.provider.model-main:llama-3.3-70b-versatile}") String modelMain,
            ObjectMapper objectMapper
    ) {
        this.modelFast    = modelFast;
        this.modelMain    = modelMain;
        this.objectMapper = objectMapper;
        this.webClient    = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        log.info("GroqAiAdapter initialized — fast={} main={}", modelFast, modelMain);
    }

    @Override
    public String chat(String systemPrompt, String userContent) {
        return chatWithModel(modelMain, systemPrompt, userContent);
    }

    @Override
    public String chatFast(String systemPrompt, String userContent) {
        return chatWithModel(modelFast, systemPrompt, userContent);
    }

    private String chatWithModel(String model, String systemPrompt, String userContent) {
        Map<String, Object> body = Map.of(
                "model",       model,
                "temperature", 0.1,
                "max_tokens",  4096,
                "messages",    List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userContent)
                )
        );

        log.debug("Calling AI model={} promptLen={} contentLen={}",
                model, systemPrompt.length(), userContent.length());

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root    = objectMapper.readTree(response);
            String   content = root.at("/choices/0/message/content").asText();
            log.debug("AI response received ({} chars) from model={}", content.length(), model);
            return content;

        } catch (Exception e) {
            throw new RuntimeException("AI provider call failed: " + e.getMessage(), e);
        }
    }
}
