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
 * <p>Two corpora are ingested:
 * <ul>
 *   <li><b>Apache Kafka</b> — producer/consumer API, topic config, consumer groups,
 *       Kafka Streams concepts — used by agents migrating from/to Kafka</li>
 *   <li><b>GCP Pub/Sub</b> — publisher, subscriber, push/pull delivery, ordering,
 *       dead-letter topics — used by agents migrating from/to GCP Pub/Sub</li>
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
                "https://spring.io/blog/2022/05/24/preparing-for-spring-boot-3-0")
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
