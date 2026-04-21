package com.migrator.common.exception;

/**
 * Thrown when a requested migration job does not exist in the system.
 */
public final class JobNotFoundException extends BasePlatformException {

    private static final String ERROR_CODE = "JOB_NOT_FOUND";

    public JobNotFoundException(String jobId) {
        super("Migration job not found with id: " + jobId, ERROR_CODE);
    }
}
