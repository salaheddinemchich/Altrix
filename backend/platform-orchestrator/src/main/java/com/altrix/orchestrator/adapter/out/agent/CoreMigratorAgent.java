package com.altrix.orchestrator.adapter.out.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.orchestrator.domain.port.out.AgentPort;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.model.ConfigFormatResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 3 — Core Migrator.
 *
 * <p>The most important agent. Takes the architecture map from Agent 1
 * and rewrites every PubSub-related Java file and config file to use
 * the Kafka equivalent.
 *
 * <p>Migration mapping:
 * <pre>
 *   @PubSubListener           → @KafkaListener
 *   PubSubTemplate            → KafkaTemplate
 *   ProjectSubscriptionName   → TopicPartition (plain string)
 *   AcknowledgeablePubsubMessage → Acknowledgment
 *   GCP credentials config    → Kafka bootstrap-servers config
 *   spring-cloud-gcp-pubsub   → spring-kafka (build file)
 * </pre>
 *
 * <p>Config format is preserved per {@link ConfigFormatResolver}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreMigratorAgent implements AgentPort {

    private final AiPort        aiPort;
    private final FileReaderPort fileReaderPort;
    private final ObjectMapper  objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an expert Java developer migrating a Spring Boot application
            from Google Cloud PubSub to Apache Kafka.

            You will receive:
            1. A list of PubSub components found in the project (topics, subscriptions, classes)
            2. The content of source files that need migration

            Your task: rewrite each file to use Apache Kafka.

            Migration rules:
            - @PubSubListener → @KafkaListener(topics = "topic-name", groupId = "migrated-group")
            - PubSubTemplate → KafkaTemplate<String, String>
            - pubSubTemplate.publish("topic", message) → kafkaTemplate.send("topic", message)
            - AcknowledgeablePubsubMessage → String payload + Acknowledgment ack
            - message.ack() → ack.acknowledge()
            - ProjectSubscriptionName → remove entirely, use plain string in @KafkaListener
            - GCP credentials in application.yml → remove GCP block, add spring.kafka.bootstrap-servers
            - In build files: remove spring-cloud-gcp-starter-pubsub, add spring-kafka

            CONFIG FORMAT: preserve the original format exactly.
            If the file is .properties, keep .properties syntax.
            If the file is .yml, keep .yml syntax.

            Respond ONLY with a valid JSON array — no markdown, no explanation:
            [
              {
                "originalPath": "src/main/java/com/example/MyListener.java",
                "newPath":      "src/main/java/com/example/MyListener.java",
                "content":      "package com.example;\\n...full rewritten file...",
                "changeType":   "MODIFIED",
                "diffSummary":  "Replaced @PubSubListener with @KafkaListener"
              }
            ]
            """;

    @Override
    public String getName()  { return "Core Migrator"; }

    @Override
    public int getOrder()    { return 3; }

    @Override
    public ProjectContext execute(ProjectContext context) {
        log.info("Agent 3 — migrating project '{}'", context.projectId());

        Map<String, String> sourceFiles =
                fileReaderPort.readSourceFiles(context.storageKey());

        if (sourceFiles.isEmpty()) {
            log.warn("No source files to migrate for project '{}'", context.projectId());
            return context;
        }

        // Determine output config format
        ConfigFormat outputFormat = ConfigFormatResolver.resolve(
                context.detectionResult() != null
                        ? context.detectionResult().configFormat() : null,
                context.configFormatPreference()
        );

        // Build user prompt with context map + file contents
        String userContent = buildUserContent(context, sourceFiles, outputFormat);

        log.debug("Sending {} files to AI for migration", sourceFiles.size());
        String aiResponse = aiPort.chat(SYSTEM_PROMPT, userContent);

        List<MigratedFile> migratedFiles = parseResponse(aiResponse);
        log.info("Agent 3 produced {} migrated files", migratedFiles.size());

        return context.withMigratedFiles(migratedFiles);
    }

    private String buildUserContent(
            ProjectContext context,
            Map<String, String> sourceFiles,
            ConfigFormat outputFormat
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("OUTPUT CONFIG FORMAT: ").append(outputFormat.name()).append("\n\n");

        sb.append("PUBSUB COMPONENTS FOUND:\n");
        sb.append("  Topics: ").append(context.pubSubTopics()).append("\n");
        sb.append("  Subscriptions: ").append(context.pubSubSubscriptions()).append("\n");
        sb.append("  Listener classes: ").append(context.listenerClasses()).append("\n");
        sb.append("  Publisher classes: ").append(context.publisherClasses()).append("\n\n");

        sb.append("FILES TO MIGRATE:\n\n");
        sourceFiles.forEach((path, content) -> {
            sb.append("// FILE: ").append(path).append("\n");
            sb.append(content).append("\n\n");
        });

        return sb.toString();
    }

    private List<MigratedFile> parseResponse(String aiResponse) {
        try {
            String cleaned = aiResponse.strip()
                    .replaceAll("^```json", "").replaceAll("```$", "").strip();

            List<Map<String, String>> rawList = objectMapper.readValue(
                    cleaned,
                    new TypeReference<>() {}
            );

            List<MigratedFile> result = new ArrayList<>();
            for (Map<String, String> raw : rawList) {
                result.add(MigratedFile.builder()
                        .originalPath(raw.getOrDefault("originalPath", "unknown"))
                        .newPath(raw.getOrDefault("newPath", raw.getOrDefault("originalPath", "unknown")))
                        .content(raw.getOrDefault("content", ""))
                        .changeType(parseChangeType(raw.get("changeType")))
                        .diffSummary(raw.getOrDefault("diffSummary", "Migrated from PubSub to Kafka"))
                        .build());
            }
            return List.copyOf(result);

        } catch (Exception e) {
            log.error("Failed to parse Agent 3 response: {}", e.getMessage());
            return List.of();
        }
    }

    private FileChangeType parseChangeType(String value) {
        if (value == null) return FileChangeType.MODIFIED;
        try { return FileChangeType.valueOf(value); }
        catch (IllegalArgumentException e) { return FileChangeType.MODIFIED; }
    }
}
