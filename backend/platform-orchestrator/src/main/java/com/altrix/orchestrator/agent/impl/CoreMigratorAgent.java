package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileMigrationCachePort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.infrastructure.ai.ContextPruner;
import com.altrix.orchestrator.infrastructure.ai.PubSubDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 3 — Core Migrator (typed pipeline variant).
 *
 * <p>Reads source files from MinIO via the {@code storageKey} stored in
 * the approved plan, prunes the file set via {@link ContextPruner} (#27),
 * then rewrites each Java file that contains Pub/Sub code using the
 * powerful AI model. Non-Java files and files without Pub/Sub code are
 * passed through unchanged.
 *
 * <p>When a {@code retryContext} is present in the approved plan (#48) it is
 * prepended to the system prompt so the model can fix previously detected issues.
 *
 * <p>Per-file AI failures are non-fatal: the original file is kept and the
 * migration summary notes the skip, so the pipeline completes even when the
 * AI is intermittently unavailable.
 */
@Slf4j
@Component("typedCoreMigratorAgent")
@RequiredArgsConstructor
public class CoreMigratorAgent implements MigrationAgent<ApprovedPlan, MigrationArtifact> {

    private static final String SYSTEM_PROMPT = """
            You are a migration expert.  Rewrite the following file (Java source,
            Maven pom.xml, Gradle build script, application.yml / application.properties,
            or Jakarta EE / Spring config XML) to migrate from Google Cloud Pub/Sub
            to Apache Kafka.

            Recognise BOTH styles of GCP Pub/Sub a project may use:

            (A) Modern Spring Cloud GCP — package org.springframework.cloud.gcp.pubsub.*:
                - @PubSubListener / @SubscriberHandler  →  @KafkaListener
                - PubSubTemplate / MessagePublisher     →  KafkaTemplate<String, String>
                - imports under google.cloud.pubsub.*   →  imports under org.springframework.kafka.*

            (B) Legacy GCP Pub/Sub REST v1 — package com.google.api.services.pubsub.*:
                - com.google.api.services.pubsub.Pubsub client
                    →  KafkaProducer<String, String>  +  KafkaConsumer<String, String>
                - com.google.api.services.pubsub.model.PubsubMessage
                    →  ProducerRecord<String, String>
                - com.google.api.services.pubsub.model.ReceivedMessage
                    →  ConsumerRecord<String, String>
                - Pubsub.Projects.Topics.publish(...)
                    →  kafkaTemplate.send(topic, value)  (or producer.send(new ProducerRecord<>(...)))
                - Pubsub.Projects.Subscriptions.pull(...)
                    →  consumer.poll(Duration.ofSeconds(N))  (or annotate the method @KafkaListener)
                - User-defined wrappers named "PubsubService", "PubSubService",
                  "PubsubClient", "PubSubClient" — replace topic/subscription
                  create / get / publish / pull calls with their Kafka equivalents.

            (C) Maven pom.xml — replace dependency declarations:
                - <artifactId>google-api-services-pubsub</artifactId>     →  REMOVE
                - <artifactId>google-cloud-pubsub</artifactId>            →  REMOVE
                - <artifactId>spring-cloud-gcp-pubsub</artifactId>        →  REMOVE
                - <artifactId>spring-cloud-gcp-starter-pubsub</artifactId> →  REMOVE
                For Spring Boot projects ADD:
                  <dependency>
                    <groupId>org.springframework.kafka</groupId>
                    <artifactId>spring-kafka</artifactId>
                  </dependency>
                For Jakarta EE / plain Java projects ADD:
                  <dependency>
                    <groupId>org.apache.kafka</groupId>
                    <artifactId>kafka-clients</artifactId>
                    <version>3.7.1</version>
                  </dependency>
                Preserve every unrelated dependency, plugin, property, and the
                surrounding XML structure exactly as is.

            (D) Gradle build.gradle / build.gradle.kts — same swap as above using
                the Gradle DSL (implementation 'org.springframework.kafka:spring-kafka'
                or implementation 'org.apache.kafka:kafka-clients:3.7.1').

            (E) application.yml / application.properties — bootstrap config:
                - REMOVE: spring.cloud.gcp.pubsub.*, GOOGLE_APPLICATION_CREDENTIALS,
                          PUBSUB_EMULATOR_HOST, gcp.pubsub.* keys
                - ADD: spring.kafka.bootstrap-servers (default: localhost:9092)
                       spring.kafka.consumer.group-id (= the application name)
                       spring.kafka.consumer.auto-offset-reset = earliest
                       spring.kafka.producer.key-serializer / value-serializer
                For a .properties file use dotted keys; for a .yaml file use the
                nested-map form.  Preserve every unrelated property/setting.

            (F) Java config classes that wire topics, publishers, subscribers (e.g.
                a "PubsubConfig" class with constants for topic names, or @Bean
                methods returning PubSubTemplate / Pubsub clients):
                - Topic-name constants stay (they're still valid Kafka topic names).
                - @Bean PubSubTemplate / @Bean Pubsub                →  @Bean KafkaTemplate
                - Topic + subscription creation via Pubsub.Projects.* → @Bean NewTopic
                  (Spring Kafka auto-creates topics declared as NewTopic beans).
                - Subscription objects → KafkaListener configuration

            Rules:
            - Preserve ALL business logic exactly.  Preserve every existing Javadoc
              and inline comment that is NOT Pub/Sub-specific.
            - Preserve package declarations, class names, and method signatures unless
              the migration requires a different argument or return type (e.g.
              ReceivedMessage → ConsumerRecord<String, String>).
            - Replace EVERY Pub/Sub import with the corresponding Kafka import.
            - For Jakarta EE / EJB @Singleton @Startup @Schedule classes, keep the
              lifecycle annotations and replace the manual pull loop body with the
              equivalent Kafka consumer call.
            - If the file genuinely contains NO Pub/Sub references after this analysis,
              return its content exactly as provided.

            HARD CONSTRAINTS — violating any of these breaks the build for the user:
            * REPLACE the implementation INLINE.  Do NOT leave the original Pub/Sub
              code (or alternative Kafka approaches) as commented-out blocks.  The
              method body must contain the real, runnable Kafka code, not a sketch
              with several options in comments.
            * Do NOT add narrative comments like `// Renamed from PubsubService`,
              `// Kafka auto-creates topics`, `// RECOMMENDED APPROACH`, or any
              other meta-commentary about the migration itself.  The output is
              source code, not a migration report.
            * Do NOT use markdown formatting inside comments — no **bold**, no
              _italic_, no `#` headers.  Comments must be valid plain Java / XML /
              YAML comments.
            * Do NOT wrap the output in markdown code fences (```java, ```xml,
              ```yaml, ```).  Return raw file content only.
            * Return the COMPLETE file.  If the file is large, prioritize finishing
              the implementation over preserving comments — but NEVER emit a
              partial file ending in `...`, `// truncated`, or an open XML tag.
            * For Kafka consumers in Java code, assume the default StringDeserializer
              for value().  Therefore `record.value()` is a String — do NOT call
              `.getAttributes()` or other Pub/Sub message methods on it.  If the
              original code used message attributes, switch to record.headers() or
              parse the value string accordingly.

            Return ONLY the complete rewritten file content (matching the original
            file's format: Java, XML, YAML, .properties).  No explanations, no
            markdown fences, no leading file path.
            """;

    private final AiPort aiPort;
    private final FileReaderPort fileReader;
    private final ContextPruner contextPruner;
    private final FileMigrationCachePort migrationCache;

    @Override
    public String getName() {
        return "Core Migrator";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public MigrationArtifact execute(ApprovedPlan input) {
        if (input == null) throw new AgentFailureException(getName(), "input ApprovedPlan was null");

        String projectId = input.plan().projectId();
        String storageKey = input.plan().storageKey();
        log.info("[{}] migrating project '{}' (approved by '{}'{})",
                getName(), projectId, input.approvedBy(),
                input.retryContext() != null ? ", retry" : "");

        if (storageKey == null || storageKey.isBlank()) {
            log.warn("[{}] no storageKey in plan — returning empty artifact", getName());
            return new MigrationArtifact(projectId, List.of(), "No files to migrate (storageKey missing)");
        }

        String effectiveSystemPrompt = input.retryContext() != null && !input.retryContext().isBlank()
                ? input.retryContext() + "\n\n" + SYSTEM_PROMPT
                : SYSTEM_PROMPT;

        try {
            Map<String, String> allFiles = fileReader.readSourceFiles(storageKey);
            PrunedContext pruned = contextPruner.prune(allFiles, input.plan());
            List<MigratedFile> migrated = migrateFiles(pruned.files(), effectiveSystemPrompt);

            // Include unchanged versions of files excluded by the pruner
            List<MigratedFile> result = new ArrayList<>(migrated);
            for (Map.Entry<String, String> entry : allFiles.entrySet()) {
                if (!pruned.files().containsKey(entry.getKey())) {
                    result.add(unchanged(entry.getKey(), entry.getValue(), "Excluded by context pruner"));
                }
            }

            long modifiedCount = result.stream()
                    .filter(f -> f.changeType() == FileChangeType.MODIFIED).count();
            String summary = "Migrated %d/%d file(s) for project '%s' (pruned %d file(s))"
                    .formatted(modifiedCount, result.size(), projectId, pruned.prunedFiles());
            return new MigrationArtifact(projectId, result, summary);
        } catch (Exception e) {
            log.error("[{}] migration failed for project '{}': {}", getName(), projectId, e.getMessage());
            throw new AgentFailureException(getName(), "file migration failed: " + e.getMessage());
        }
    }

    private List<MigratedFile> migrateFiles(Map<String, String> sourceFiles, String systemPrompt) {
        List<MigratedFile> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourceFiles.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();

            if (!PubSubDetector.isMigratableFile(path)) {
                result.add(unchanged(path, content, "Not a migratable file type"));
                continue;
            }
            if (!PubSubDetector.hasPubSubCode(content)) {
                result.add(unchanged(path, content, "No Pub/Sub code detected"));
                continue;
            }

            // #28 — content-addressed cache.  Key includes the system prompt so
            // a prompt tweak forces a fresh AI call.  Cuts iterative-dev cost to
            // zero when nothing in the source changed.
            String cacheKey = computeCacheKey(systemPrompt, path, content);
            Optional<String> cached = migrationCache.get(cacheKey);
            if (cached.isPresent()) {
                String migrated = cached.get();
                FileChangeType changeType = migrated.equals(content)
                        ? FileChangeType.UNCHANGED : FileChangeType.MODIFIED;
                result.add(MigratedFile.builder()
                        .originalPath(path).newPath(path).content(migrated)
                        .changeType(changeType).diffSummary("Migrated Pub/Sub → Kafka (cache hit)")
                        .build());
                continue;
            }

            try {
                String raw = aiPort.chat(systemPrompt, "File: " + path + "\n\n" + content);
                String migrated = stripMarkdownFences(raw);

                if (looksTruncated(migrated, content)) {
                    log.warn("[{}] AI output for '{}' looks truncated ({} chars vs {} original) — keeping original",
                            getName(), path, migrated.length(), content.length());
                    result.add(unchanged(path, content, "Migration skipped — AI output truncated"));
                    continue;
                }

                FileChangeType changeType = migrated.equals(content)
                        ? FileChangeType.UNCHANGED : FileChangeType.MODIFIED;
                // Only cache successful migrations — never cache an unchanged
                // pass-through (saves no AI call) nor a truncation fallback.
                if (changeType == FileChangeType.MODIFIED) {
                    migrationCache.put(cacheKey, migrated);
                }
                result.add(MigratedFile.builder()
                        .originalPath(path).newPath(path).content(migrated)
                        .changeType(changeType).diffSummary("Migrated Pub/Sub → Kafka")
                        .build());
            } catch (Exception e) {
                log.warn("[{}] AI failed for '{}', keeping original: {}", getName(), path, e.getMessage());
                result.add(unchanged(path, content, "Migration skipped — AI unavailable"));
            }
        }
        return result;
    }

    /**
     * Content-addressed cache key — {@code SHA-256(systemPrompt + "|" + path
     * + "|" + content)}.  Including the prompt means tweaking it
     * automatically invalidates every cached entry; including the path keeps
     * two unrelated files with identical content from sharing a cache entry.
     */
    static String computeCacheKey(String systemPrompt, String path, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE — falling back here is fine.
            return Integer.toHexString((systemPrompt + path + content).hashCode());
        }
    }

    private static MigratedFile unchanged(String path, String content, String reason) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.UNCHANGED).diffSummary(reason)
                .build();
    }

    /**
     * Removes leading/trailing markdown code fences ({@code ```java}, {@code ```xml},
     * {@code ```}) that AI models stubbornly add despite the prompt forbidding them.
     * Without this strip the fence ends up as the first line of the migrated file,
     * making the source uncompilable.
     */
    static String stripMarkdownFences(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String s = raw.strip();

        // Leading fence: ``` optionally followed by a language tag and a newline
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                // Drop the ``` and any language tag on the same line
                s = s.substring(firstNewline + 1);
            } else {
                // Pathological case: only the fence, no body
                return "";
            }
        }

        // Trailing fence
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
            // Strip whitespace that may sit just before the closing fence
            int lastNonWs = s.length() - 1;
            while (lastNonWs >= 0 && Character.isWhitespace(s.charAt(lastNonWs))) lastNonWs--;
            s = s.substring(0, lastNonWs + 1);
        }

        return s;
    }

    /**
     * Defence-in-depth against silently truncated AI output: if the migrated
     * content is implausibly short compared to the input AND the input wasn't
     * trivial to begin with, assume the model hit max_tokens mid-stream and
     * keep the original file.  A truncated rewrite (e.g. a pom.xml with no
     * closing tag, a Java file with no closing brace) is strictly worse than
     * no rewrite — it breaks the build instead of preserving it.
     */
    static boolean looksTruncated(String migrated, String original) {
        if (migrated == null || original == null) return false;
        int origLen = original.length();
        int migLen  = migrated.length();
        // Don't trip the guard on small files — they legitimately shrink a lot
        // (e.g. a 200-byte pom snippet that loses one dependency block).
        if (origLen < 500) return false;
        // Less than 30 % the original size is the red flag.
        return migLen < origLen * 0.30;
    }
}
