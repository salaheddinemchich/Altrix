package com.altrix.orchestrator.domain.model.rag;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Per-file RAG retrieval provenance for a migration session (#1).
 *
 * <p>Whereas {@link RagIndexManifest} captures <em>which source files</em>
 * were indexed for retrieval, this model captures <em>which documentation
 * chunks were used to migrate each file</em>.  Powers the JobDetail
 * timeline's Index step: instead of an opaque list of "indexed files",
 * the reviewer sees for every migrated file the exact doc URLs the AI
 * was anchored to.
 *
 * <p>One row per session.  An empty {@link #perFile} list means the
 * migrator ran without RAG context (either the embedding store is
 * disabled, or no relevant doc chunks were found).
 *
 * @param sessionId  the migration session this provenance belongs to.
 * @param perFile    map of source-file path → ordered list of doc chunks
 *                   that were retrieved as context when migrating it.
 *                   First entry is the most-relevant.
 * @param generatedAt timestamp when the migration produced this trace.
 */
public record FileProvenance(
        String sessionId,
        Map<String, List<DocReference>> perFile,
        Instant generatedAt
) {
    public FileProvenance {
        perFile = perFile != null ? Map.copyOf(perFile) : Map.of();
        if (generatedAt == null) generatedAt = Instant.now();
    }

    /**
     * One retrieved documentation chunk's identity + snippet.  Kept small
     * on purpose: the full chunk text already lives in the embedding
     * store; this record just needs enough to render in the UI and link
     * back to the source page.
     *
     * @param logicalPath e.g. "kafka/producers" — the logical doc bucket.
     * @param sourceUrl   canonical URL the content was fetched from.
     * @param snippet     first ~200 chars of the retrieved chunk text,
     *                    so the UI can show what passage was matched.
     */
    public record DocReference(
            String logicalPath,
            String sourceUrl,
            String snippet
    ) {}
}
