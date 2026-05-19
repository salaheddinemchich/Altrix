package com.altrix.common.exception;

/**
 * Thrown when remote Git repository ingestion fails: invalid URL, authentication
 * failure, branch not found, repository too large, or transport-level error.
 *
 * <p>Issue #74: JGit-based repository cloner.
 */
public final class RepositoryIngestionException extends BasePlatformException {

    private static final String ERROR_CODE = "REPOSITORY_INGESTION_FAILED";

    public RepositoryIngestionException(String message) {
        super(message, ERROR_CODE);
    }

    public RepositoryIngestionException(String message, Throwable cause) {
        super(message, ERROR_CODE);
        initCause(cause);
    }
}
