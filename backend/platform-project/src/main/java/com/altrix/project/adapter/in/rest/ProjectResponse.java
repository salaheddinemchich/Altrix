package com.altrix.project.adapter.in.rest;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectSource;
import com.altrix.project.domain.model.ProjectStatus;

import java.time.Instant;
import java.util.List;

/**
 * HTTP response DTO for project queries.
 *
 * <p>Never exposes the internal storage key — that is an infrastructure
 * detail the client does not need.
 *
 * <p>{@code repoUrl}, {@code trackedBranch} and {@code source} are
 * exposed so downstream services (e.g. platform-orchestrator's
 * apply-workflow access check) can resolve the remote repository
 * without a second round-trip into project storage.  Returns
 * {@code null} for projects that were not created from a remote
 * (legacy ZIP uploads).
 */
public record ProjectResponse(
        String id,
        String name,
        ProjectStatus status,
        BuildSystem buildSystem,
        ConfigFormat configFormat,
        DetectedFramework framework,
        boolean eligibleForMigration,
        List<String> detectedTechnologies,
        Instant createdAt,
        String repoUrl,
        String trackedBranch,
        ProjectSource source
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getStatus(),
                project.getBuildSystem(),
                project.getConfigFormat(),
                project.getFramework(),
                project.isEligibleForMigration(),
                project.getDetectedTechnologies(),
                project.getCreatedAt(),
                project.getRepoUrl(),
                project.getTrackedBranch(),
                project.getSource()
        );
    }
}
