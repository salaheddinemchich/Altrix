package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig.Page;
import com.altrix.orchestrator.infrastructure.rag.DomainAllowListValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Ingests migration-relevant documentation into the vector store at
 * startup.  The corpus (which URLs, which allow-listed hosts) lives in
 * {@link DocumentationCorpusConfig} so it is fully YAML/env-driven — no
 * recompile to add or drop a reference page.
 *
 * <p>Documentation is fetched once per logical path; if the content hash
 * already exists in the embedding store the fetch is skipped entirely
 * (no re-embedding cost).
 *
 * <p>Every URL passes through {@link DomainAllowListValidator} before any
 * network call.  An entry whose URL isn't on the allow-list is logged and
 * skipped — never reaches the {@link DocumentationFetchPort} adapter.
 *
 * <p>Direction is strictly <b>GCP Pub/Sub → Apache Kafka</b>: the corpus
 * is curated to that migration.  Reverse-direction guides should not be
 * configured.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentationIngestionService {

    private final EmbeddingStorePort embeddingStore;
    private final DocumentationFetchPort docFetch;
    private final DomainAllowListValidator allowList;
    private final DocumentationCorpusConfig corpus;

    public void ingestOnStartup() {
        List<Page> pages = corpus.pages();
        if (pages.isEmpty()) {
            log.warn("Documentation corpus is empty — skipping ingestion");
            return;
        }
        log.info("Documentation ingestion started ({} pages, {} allowed domain(s))",
                pages.size(), corpus.allowedDomains().size());

        // Prune DOCUMENTATION rows whose logical-path is no longer in the
        // configured corpus.  Without this step, removing a page from the
        // YAML leaves its embeddings sitting in pgvector and similarity
        // search keeps surfacing them — exactly what produced the leftover
        // "migration/kafka-to-pubsub" hits the user saw in the UI after
        // we corrected the corpus direction.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Page p : pages) keep.add(p.logicalPath());
        try {
            embeddingStore.deleteDocumentationNotIn(keep);
        } catch (Exception e) {
            // Pruning failure is non-fatal — ingestion can still proceed.
            log.warn("Could not prune stale documentation rows: {}", e.getMessage());
        }

        int ingested = 0;
        int skippedExisting = 0;
        int skippedDisallowed = 0;
        int failed = 0;

        for (Page page : pages) {
            if (!allowList.isAllowed(page.url())) {
                // DomainAllowListValidator already logged WARN; just count.
                skippedDisallowed++;
                continue;
            }
            try {
                if (embeddingStore.documentationExists(page.logicalPath())) {
                    log.debug("Docs already indexed, skipping: {}", page.logicalPath());
                    skippedExisting++;
                    continue;
                }
                String content = docFetch.fetchPage(page.url());
                if (content.isBlank()) {
                    log.warn("Empty content from {}, skipping", page.url());
                    failed++;
                    continue;
                }
                List<DocumentChunk> chunks = chunk(page, content);
                embeddingStore.upsert(chunks);
                ingested++;
                log.info("Ingested: {} ({} chunks)", page.logicalPath(), chunks.size());
            } catch (Exception e) {
                failed++;
                log.warn("Failed to ingest {}: {}", page.logicalPath(), e.getMessage());
            }
        }
        log.info("Documentation ingestion complete — {} ingested, {} skipped (already indexed), "
                        + "{} skipped (disallowed), {} failed",
                ingested, skippedExisting, skippedDisallowed, failed);
    }

    /** Split on double-newlines (paragraph boundaries), max 800 chars per chunk. */
    private List<DocumentChunk> chunk(Page page, String content) {
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

    private DocumentChunk toChunk(Page page, int index, String text) {
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
}
