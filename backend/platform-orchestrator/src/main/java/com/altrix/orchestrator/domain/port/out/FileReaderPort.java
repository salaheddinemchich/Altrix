package com.altrix.orchestrator.domain.port.out;

import java.util.Map;

/**
 * Secondary port — reads source files from storage.
 * The domain never imports MinIO.
 */
public interface FileReaderPort {

    /**
     * Reads all Java and config files from the uploaded project ZIP.
     *
     * @param storageKey MinIO object key of the uploaded ZIP
     * @return map of relative file path → file content as UTF-8 string
     */
    Map<String, String> readSourceFiles(String storageKey);

    /**
     * Reads ALL text files from the uploaded project ZIP (no extension filter).
     * Used by the RAG indexer which decides per-extension what to embed.
     *
     * @param storageKey MinIO object key of the uploaded ZIP
     * @return map of relative file path → file content as UTF-8 string
     */
    Map<String, String> readAllFiles(String storageKey);

    /**
     * Reads a single file by its path inside the uploaded ZIP (#119).
     * Used by the diff endpoint so the reviewer can see before/after side
     * by side without loading the entire archive.
     *
     * @param storageKey MinIO object key of the uploaded ZIP
     * @param path       repository-relative path of the file to read
     * @return file content as UTF-8 string, or {@code null} if the path is
     *         not present in the archive
     */
    String readSingleFile(String storageKey, String path);

    /**
     * Enumerates every entry path in the project ZIP — no content read, no
     * extension/skip filtering.  Powers the file-tree view in the diff
     * viewer so the reviewer sees the full project structure (including
     * tests, build files, resources) and not just the migration targets.
     *
     * @param storageKey MinIO object key of the uploaded ZIP
     * @return ordered set of repository-relative paths
     */
    java.util.Set<String> listAllPaths(String storageKey);
}
