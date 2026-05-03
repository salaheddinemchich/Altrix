package com.altrix.orchestrator.adapter.out.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.port.out.AgentPort;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchitectureAnalyzerAgent implements AgentPort {

    private final AiPort         aiPort;
    private final FileReaderPort  fileReaderPort;
    private final ObjectMapper    objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are a Java expert analyzing a Spring Boot application that uses Google Cloud PubSub.
            Identify every PubSub component in the source files provided.

            Look for:
            - PubSubTemplate usages and .publish() calls
            - @ServiceActivator on message handler methods
            - PubSubInboundChannelAdapter beans
            - Topic and subscription string literals
            - Classes that import com.google.cloud.spring.pubsub

            Respond ONLY with valid JSON — no markdown, no explanation:
            {
              "pubSubTopics":        ["topic-name"],
              "pubSubSubscriptions": ["subscription-name"],
              "listenerClasses":     ["com.example.MyListener"],
              "publisherClasses":    ["com.example.MyPublisher"]
            }
            """;

    @Override
    public String getName()  { return "Architecture Analyzer"; }

    @Override
    public int getOrder()    { return 1; }

    @Override
    public ProjectContext execute(ProjectContext context) {
        log.info("Agent 1 — storageKey='{}'", context.storageKey());

        if (context.storageKey() == null || context.storageKey().isBlank()) {
            log.warn("No storageKey — skipping file read");
            return context;
        }

        Map<String, String> sourceFiles = fileReaderPort.readSourceFiles(context.storageKey());
        if (sourceFiles.isEmpty()) {
            log.warn("No source files found at '{}'", context.storageKey());
            return context;
        }

        StringBuilder userContent = new StringBuilder("Analyze these source files:\n\n");
        sourceFiles.forEach((path, content) ->
                userContent.append("// FILE: ").append(path).append("\n")
                           .append(content).append("\n\n"));

        log.info("Sending {} files to AI (fast model)", sourceFiles.size());

        // Use fast model for analysis — cheaper tokens, respects rate limit
        String aiResponse = aiPort.chatFast(SYSTEM_PROMPT, userContent.toString());
        return parseAndEnrich(context, aiResponse);
    }

    private ProjectContext parseAndEnrich(ProjectContext context, String aiResponse) {
        try {
            String cleaned = aiResponse.strip()
                    .replaceAll("(?s)^```json\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .strip();
            JsonNode root = objectMapper.readTree(cleaned);

            List<String> topics        = readStringList(root, "pubSubTopics");
            List<String> subscriptions = readStringList(root, "pubSubSubscriptions");
            List<String> listeners     = readStringList(root, "listenerClasses");
            List<String> publishers    = readStringList(root, "publisherClasses");

            log.info("Agent 1 found: topics={} subs={} listeners={} publishers={}",
                    topics, subscriptions, listeners, publishers);

            return context
                    .withPubSubTopics(topics)
                    .withPubSubSubscriptions(subscriptions)
                    .withListenerClasses(listeners)
                    .withPublisherClasses(publishers);

        } catch (Exception e) {
            log.error("Failed to parse Agent 1 response: {} — raw: {}",
                    e.getMessage(), aiResponse.substring(0, Math.min(200, aiResponse.length())));
            return context;
        }
    }

    private List<String> readStringList(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(n -> result.add(n.asText()));
        return List.copyOf(result);
    }
}
