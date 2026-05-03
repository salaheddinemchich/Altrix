package com.altrix.project.domain.port.out;

import java.io.InputStream;

/**
 * Secondary port — driven side.
 *
 * <p>Defines what the domain needs from a file storage system.
 * The domain service never imports MinIO. It calls this interface.
 * The MinIO adapter implements this interface.
 */
public interface FileStoragePort {

    /**
     * Stores a file and returns the storage key (object name) that
     * can be used to retrieve it later.
     *
     * @param content        raw file bytes
     * @param contentLength  size in bytes
     * @param contentType    MIME type, e.g. {@code "application/zip"}
     * @return the storage key assigned to this file
     */
    String store(InputStream content, long contentLength, String contentType);

    /**
     * Retrieves a previously stored file by its storage key.
     *
     * @param storageKey  the key returned by {@link #store}
     * @return an {@link InputStream} of the file content
     */
    InputStream retrieve(String storageKey);
}
