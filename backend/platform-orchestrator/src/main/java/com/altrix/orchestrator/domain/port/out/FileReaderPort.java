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
}
