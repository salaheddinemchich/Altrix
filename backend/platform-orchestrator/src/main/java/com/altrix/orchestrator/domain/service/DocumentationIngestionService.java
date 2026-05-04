package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Ingests migration-relevant documentation into the vector store at startup.
 *
 * <p>Documentation is fetched once per logical path; if the content hash already
 * exists the fetch is skipped entirely (no re-embedding cost).
 *
 * <p>Four corpora are ingested:
 * <ul>
 *   <li><b>Apache Kafka</b> — producer/consumer API, topic config, consumer groups,
 *       Kafka Streams, Spring Kafka</li>
 *   <li><b>GCP Pub/Sub</b> — publisher, subscriber, push/pull, ordering,
 *       dead-letter topics, Spring Cloud GCP</li>
 *   <li><b>Migration patterns</b> — Kafka-to-Pub/Sub guide, Spring Boot 3 migration</li>
 *   <li><b>Jakarta EE / Java EE</b> — JMS 3.x, CDI Events, MicroProfile Reactive Messaging,
 *       raw kafka-clients, GCP Java client, Quarkus+Kafka/PubSub, Open Liberty, GCP auth —
 *       covers pure Java EE apps that do not use Spring</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationIngestionService {

    private final EmbeddingStorePort    embeddingStore;
    private final DocumentationFetchPort docFetch;

    /** Documentation pages to ingest — logical path + canonical URL pairs. */
    private static final List<DocPage> PAGES = List.of(
        // ── Apache Kafka ──────────────────────────────────────────────────────
        new DocPage("kafka/introduction",
                "https://kafka.apache.org/documentation/#gettingStarted"),
        new DocPage("kafka/producers",
                "https://kafka.apache.org/documentation/#producerapi"),
        new DocPage("kafka/consumers",
                "https://kafka.apache.org/documentation/#consumerapi"),
        new DocPage("kafka/consumer-groups",
                "https://kafka.apache.org/documentation/#intro_consumers"),
        new DocPage("kafka/topic-config",
                "https://kafka.apache.org/documentation/#topicconfigs"),
        new DocPage("kafka/streams-concepts",
                "https://kafka.apache.org/documentation/streams/"),
        new DocPage("kafka/connect-overview",
                "https://kafka.apache.org/documentation/#connect"),
        new DocPage("kafka/spring-boot",
                "https://docs.spring.io/spring-kafka/reference/quick-tour.html"),

        // ── GCP Pub/Sub ───────────────────────────────────────────────────────
        new DocPage("gcp-pubsub/overview",
                "https://cloud.google.com/pubsub/docs/overview"),
        new DocPage("gcp-pubsub/publisher",
                "https://cloud.google.com/pubsub/docs/publish-receive-messages-client-library"),
        new DocPage("gcp-pubsub/subscriber",
                "https://cloud.google.com/pubsub/docs/pull"),
        new DocPage("gcp-pubsub/push-subscriptions",
                "https://cloud.google.com/pubsub/docs/push"),
        new DocPage("gcp-pubsub/ordering",
                "https://cloud.google.com/pubsub/docs/ordering"),
        new DocPage("gcp-pubsub/dead-letter",
                "https://cloud.google.com/pubsub/docs/dead-letter-topics"),
        new DocPage("gcp-pubsub/spring",
                "https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#spring-integration-channel-adapters-for-cloud-pub-sub"),

        // ── Migration patterns ────────────────────────────────────────────────
        new DocPage("migration/kafka-to-pubsub",
                "https://cloud.google.com/pubsub/docs/migrating-from-kafka-to-pubsub"),
        new DocPage("migration/spring-boot-3",
                "https://spring.io/blog/2022/05/24/preparing-for-spring-boot-3-0"),

        // ── Jakarta EE / Java EE — messaging without Spring ───────────────────
        // JMS 3.x (Jakarta Messaging) — the standard Java EE/Jakarta EE messaging API.
        // Agents migrating from JMS to Kafka or Pub/Sub need this reference.
        new DocPage("jakarta-ee/messaging-overview",
                "https://jakarta.ee/specifications/messaging/3.1/"),
        new DocPage("jakarta-ee/messaging-api",
                "https://jakarta.ee/specifications/messaging/3.1/apidocs/"),

        // CDI Events — the Jakarta EE in-process event bus; often confused with
        // message brokers during migration analysis.
        new DocPage("jakarta-ee/cdi-events",
                "https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0.html#events"),

        // MicroProfile Reactive Messaging — bridges Jakarta EE apps to Kafka/Pub/Sub
        // via @Incoming / @Outgoing annotations (Quarkus, Open Liberty, Payara).
        new DocPage("jakarta-ee/microprofile-reactive-messaging",
                "https://download.eclipse.org/microprofile/microprofile-reactive-messaging-3.0/microprofile-reactive-messaging-spec-3.0.html"),

        // Kafka with pure Jakarta EE (no Spring) — uses kafka-clients directly;
        // agents must recognise both Spring-Kafka and raw kafka-clients patterns.
        new DocPage("jakarta-ee/kafka-clients-producer",
                "https://kafka.apache.org/documentation/#producerconfigs"),
        new DocPage("jakarta-ee/kafka-clients-consumer",
                "https://kafka.apache.org/documentation/#consumerconfigs"),

        // GCP Pub/Sub Java client (pure Java, no framework) — used in Jakarta EE
        // deployments that call Pub/Sub via the Google Cloud client library directly.
        new DocPage("jakarta-ee/gcp-pubsub-java-client",
                "https://cloud.google.com/pubsub/docs/reference/libraries#client-libraries-install-java"),

        // Quarkus + Kafka — the most common Jakarta EE runtime for Kafka migrations.
        new DocPage("jakarta-ee/quarkus-kafka",
                "https://quarkus.io/guides/kafka"),

        // Quarkus + GCP Pub/Sub via Reactive Messaging connector.
        new DocPage("jakarta-ee/quarkus-pubsub",
                "https://quarkus.io/guides/reactive-messaging-google-pubsub"),

        // Open Liberty + Kafka (MicroProfile Reactive Messaging on Liberty).
        new DocPage("jakarta-ee/openliberty-kafka",
                "https://openliberty.io/docs/latest/reactive-messaging.html"),

        // Jakarta EE + GCP configuration best practices (service accounts, ADC,
        // workload identity) — agents need this for GCP authentication context.
        new DocPage("jakarta-ee/gcp-auth-java",
                "https://cloud.google.com/docs/authentication/client-libraries")
    );

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        log.info("Documentation ingestion started ({} pages)", PAGES.size());
        int ingested = 0;
        for (DocPage page : PAGES) {
            try {
                if (embeddingStore.documentationExists(page.logicalPath())) {
                    log.debug("Docs already indexed, skipping: {}", page.logicalPath());
                    continue;
                }
                String content = docFetch.fetchPage(page.url());
                if (content.isBlank()) {
                    log.warn("Empty content from {}, skipping", page.url());
                    continue;
                }
                List<DocumentChunk> chunks = chunk(page, content);
                embeddingStore.upsert(chunks);
                ingested++;
                log.info("Ingested: {} ({} chunks)", page.logicalPath(), chunks.size());
            } catch (Exception e) {
                log.warn("Failed to ingest {}: {}", page.logicalPath(), e.getMessage());
            }
        }
        log.info("Documentation ingestion complete — {} pages ingested", ingested);
    }

    private List<DocumentChunk> chunk(DocPage page, String content) {
        // Split on double-newlines (paragraph boundaries), max 800 chars per chunk
        List<DocumentChunk> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\n{2,}");
        StringBuilder buffer = new StringBuilder();
        int index = 0;

        for (String paragraph : paragraphs) {
            if (buffer.length() + paragraph.length() > 800 && !buffer.isEmpty()) {
                chunks.add(toChunk(page, index++, buffer.toString().trim()));
                buffer.setLength(0);
            }
            buffer.append(paragraph).append("\n\n");
        }
        if (!buffer.isEmpty()) {
            chunks.add(toChunk(page, index, buffer.toString().trim()));
        }
        return chunks;
    }

    private DocumentChunk toChunk(DocPage page, int index, String text) {
        return DocumentChunk.documentation(page.logicalPath(), index, text, sha256(text), page.url());
    }

    private String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private record DocPage(String logicalPath, String url) {}
}
