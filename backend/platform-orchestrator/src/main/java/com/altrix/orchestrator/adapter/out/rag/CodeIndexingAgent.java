package com.altrix.orchestrator.adapter.out.rag;

import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.model.rag.RagIndexManifest;
import com.altrix.orchestrator.domain.port.out.CodeIndexingPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Reads the uploaded project ZIP, chunks every source file, embeds the chunks,
 * and stores them in the vector store — before any migration agent runs.
 *
 * <p>Only text-based source files are indexed (Java, Kotlin, YAML, properties,
 * XML build files). Binary files are skipped.
 *
 * <p>Deduplication: files whose content hash already exists in the store are
 * skipped — repeated runs on the same project are cheap.
 *
 * <p>Returns a {@link RagIndexManifest} so the caller (OrchestratorService)
 * can persist the indexed-file list for later display in the JobDetail
 * timeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeIndexingAgent implements CodeIndexingPort {

    private static final int CHUNK_SIZE_CHARS = 1200;
    private static final int CHUNK_OVERLAP_CHARS = 200;
    private static final Set<String> INDEXABLE_EXTENSIONS = Set.of(
            ".java", ".kt", ".groovy",
            ".yml", ".yaml", ".properties", ".xml",
            ".json", ".gradle", ".gradle.kts", ".toml",
            ".sql", ".md"
    );

    private final FileReaderPort fileReader;
    private final EmbeddingStorePort embeddingStore;
    private final ProgressNotifierPort progressNotifier;

    /**
     * Index the project — called by OrchestratorService before the agent pipeline.
     *
     * <p>Emits granular progress events so the JobDetail timeline can show
     * the user "what is being indexed" instead of just a binary RUNNING/DONE.
     */
    @Override
    public RagIndexManifest index(ProjectContext context) {
        String jobId = context.jobId();
        log.info("Job '{}' — indexing source files for RAG", jobId);

        progressNotifier.notify(jobId, "RAG Indexer", "RUNNING", "Reading project files…");
        Map<String, String> files = fileReader.readAllFiles(context.storageKey());
        progressNotifier.notify(jobId, "RAG Indexer", "RUNNING", "Chunking " + files.size() + " file(s) for embedding…");

        List<DocumentChunk> chunks = new ArrayList<>();
        // Sorted set so the manifest is stable across runs and renders
        // alphabetically in the UI.
        SortedSet<String> indexedPaths = new TreeSet<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            if (!isIndexable(path) || content.isBlank()) continue;
            indexedPaths.add(path);

            List<String> textChunks = splitIntoChunks(content);
            for (int i = 0; i < textChunks.size(); i++) {
                String text = textChunks.get(i);
                chunks.add(DocumentChunk.sourceCode(
                        context.projectId(), path, i, text, sha256(text)));
            }
        }

        log.info("Job '{}' — {} chunks from {} files, upserting to vector store", jobId, chunks.size(), files.size());
        progressNotifier.notify(jobId, "RAG Indexer", "RUNNING",
                "Embedding " + chunks.size() + " chunk(s) from " + indexedPaths.size() + " file(s)…");
        try {
            embeddingStore.upsert(chunks);
            progressNotifier.notify(jobId, "RAG Indexer", "DONE",
                    "Indexed " + chunks.size() + " chunk(s) from " + indexedPaths.size() + " file(s)");
        } catch (IllegalStateException e) {
            // RAG embedding model is disabled (no OpenAI/Ollama key). Continue
            // without semantic search — agents still receive full file content
            // through their normal context payload, so migration still works.
            log.warn("Job '{}' — RAG indexing skipped: {}", jobId, e.getMessage());
            progressNotifier.notify(jobId, "RAG Indexer", "DONE",
                    "Skipped — embedding model not configured");
            // Even when embedding was skipped, return the set of files we
            // WOULD have indexed so the UI can still show what got
            // considered.  chunkCount=0 signals "embedding disabled".
            return new RagIndexManifest(context.projectId(),
                    new ArrayList<>(indexedPaths), indexedPaths.size(), 0, Instant.now());
        }

        return new RagIndexManifest(
                context.projectId(),
                new ArrayList<>(indexedPaths),
                indexedPaths.size(),
                chunks.size(),
                Instant.now());
    }

    /**
     * Sliding-window chunker with overlap so context isn't lost at boundaries.
     */
    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE_CHARS, text.length());
            chunks.add(text.substring(start, end));
            start += CHUNK_SIZE_CHARS - CHUNK_OVERLAP_CHARS;
            if (start >= text.length()) break;
        }
        return chunks;
    }

    private boolean isIndexable(String path) {
        String lower = path.toLowerCase();
        return INDEXABLE_EXTENSIONS.stream().anyMatch(lower::endsWith);
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
