package com.altrix.project.domain.port.in;

import com.altrix.project.domain.model.Project;

import java.util.List;

/**
 * Primary port — driving side — CQRS Query.
 *
 * <p>Read-only operations for project data.
 * Completely separate from {@link UploadProjectUseCase} (the command side).
 */
public interface GetProjectQuery {

    /**
     * Retrieves a project by its unique ID.
     *
     * @throws com.altrix.common.exception.ProjectNotFoundException if not found
     */
    Project findById(String projectId);

    /**
     * Returns all projects belonging to a specific user,
     * ordered by creation date descending (newest first).
     */
    List<Project> findAllByUserId(String userId);
}
