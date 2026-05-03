package com.altrix.project.adapter.in.rest;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectStatus;

import java.time.Instant;

/**
 * HTTP response DTO returned after a successful upload.
 *
 * <p>Never exposes the internal storage key — that is an infrastructure
 * detail the client does not need.
 */
public record ProjectResponse(
        String           id,
        String           name,
        ProjectStatus    status,
        BuildSystem      buildSystem,
        ConfigFormat     configFormat,
        DetectedFramework framework,
        Instant          createdAt
) {
    /** Factory method — maps domain entity to response DTO. */
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getStatus(),
                project.getBuildSystem(),
                project.getConfigFormat(),
                project.getFramework(),
                project.getCreatedAt()
        );
    }
}
