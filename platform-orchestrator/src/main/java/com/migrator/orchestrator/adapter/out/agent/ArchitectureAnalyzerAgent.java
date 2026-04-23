package com.migrator.orchestrator.adapter.out.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrator.common.domain.model.ProjectContext;
import com.migrator.orchestrator.domain.port.out.AgentPort;
import com.migrator.orchestrator.domain.port.out.AiPort;
import com.migrator.orchestrator.domain.port.out.FileReaderPort;
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

    private final AiPort        aiPort;
    private final FileReaderPort fileReaderPort;
    private final ObjectMapper  objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an expert Java developer analysing a Spring Boot or Java EE application
            that uses Google Cloud PubSub.

            Your task: identify EVERY PubSub-related component in the provided source files.

            Look for:
            - @PubSubListener annotations
            - PubSubTemplate usages (.publish() calls)
            - ProjectSubscriptionName references
            - MessagePublisher beans
            - Topic and subscription string literals

            Respond ONLY with a valid JSON object — no markdown, no explanation:
            {
              "pubSubTopics":        ["topic-name-1"],
              "pubSubSubscriptions": ["subscription-name-1"],
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
        log.info("Agent 1 — reading source files storageKey='{}'", context.storageKey());

        if (context.storageKey() == null || context.storageKey().isBlank()) {
            log.warn("No storageKey in context — skipping file read");
            return context;
        }

        Map<String, String> sourceFiles = fileReaderPort.readSourceFiles(context.storageKey());

        if (sourceFiles.isEmpty()) {
            log.warn("No source files found for storageKey '{}'", context.storageKey());
            return context;
        }

        StringBuilder userContent = new StringBuilder("Analyse these source files:\n\n");
        sourceFiles.forEach((path, content) ->
                userContent.append("// FILE: ").append(path).append("\n")
                           .append(content).append("\n\n"));

        log.debug("Sending {} source files to AI", sourceFiles.size());
        String aiResponse = aiPort.chat(SYSTEM_PROMPT, userContent.toString());

        return parseAndEnrich(context, aiResponse);
    }

    private ProjectContext parseAndEnrich(ProjectContext context, String aiResponse) {
        try {
            String cleaned = aiResponse.strip()
                    .replaceAll("^```json", "").replaceAll("```$", "").strip();
            JsonNode root = objectMapper.readTree(cleaned);

            List<String> topics        = readStringList(root, "pubSubTopics");
            List<String> subscriptions = readStringList(root, "pubSubSubscriptions");
            List<String> listeners     = readStringList(root, "listenerClasses");
            List<String> publishers    = readStringList(root, "publisherClasses");

            log.info("Agent 1 found: {} topics, {} subscriptions, {} listeners, {} publishers",
                    topics.size(), subscriptions.size(), listeners.size(), publishers.size());

            return context
                    .withPubSubTopics(topics)
                    .withPubSubSubscriptions(subscriptions)
                    .withListenerClasses(listeners)
                    .withPublisherClasses(publishers);

        } catch (Exception e) {
            log.error("Failed to parse Agent 1 response: {}", e.getMessage());
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
