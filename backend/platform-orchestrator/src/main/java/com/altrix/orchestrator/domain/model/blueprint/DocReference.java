package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;

/**
 * Pointer to a documentation page that was fetched + indexed for this
 * project's migration.  Stored at the blueprint level so the UI can
 * list "the references this project's analysis pulled in" alongside the
 * per-feature {@link BlueprintFeature#docRefs()} links.
 *
 * <p>Distinct from {@code DocumentChunk}: {@code DocReference} carries
 * only the page-level metadata the UI needs to display + a back-link to
 * the source URL.  Actual chunk content is read from pgvector via
 * {@code EmbeddingStorePort.findRelevant(...)} when the migrator asks
 * for it.
 *
 * @param logicalPath stable identifier matching {@code DocumentChunk#filePath()}
 *                    for DOCUMENTATION-type chunks ({@code "kafka/producers"},
 *                    {@code "gcp-pubsub/ordering"}, …).
 * @param sourceUrl   the canonical URL the page was fetched from.
 * @param fetchedAtMs epoch millis the page was first indexed in this
 *                    blueprint's run; 0 when carried from a previous run's
 *                    cache.
 * @param fetchedVia  {@link Source#MCP} when an MCP {@code fetch}-style
 *                    tool was used; {@link Source#HTTP} when the plain
 *                    fallback adapter served it.
 * @param chunkCount  number of chunks stored in pgvector for this page;
 *                    informational only.
 */
public record DocReference(
        String logicalPath,
        String sourceUrl,
        long fetchedAtMs,
        Source fetchedVia,
        int chunkCount
) implements Serializable {

    public DocReference {
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("logicalPath required");
        if (fetchedVia == null) fetchedVia = Source.HTTP;
        if (chunkCount < 0) chunkCount = 0;
    }

    public enum Source { MCP, HTTP }
}
