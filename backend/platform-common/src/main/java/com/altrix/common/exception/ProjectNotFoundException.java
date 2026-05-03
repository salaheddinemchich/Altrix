package com.altrix.common.exception;

/**
 * Thrown when a requested project does not exist in the system.
 */
public final class ProjectNotFoundException extends BasePlatformException {

    private static final String ERROR_CODE = "PROJECT_NOT_FOUND";

    public ProjectNotFoundException(String projectId) {
        super("Project not found with id: " + projectId, ERROR_CODE);
    }
}
