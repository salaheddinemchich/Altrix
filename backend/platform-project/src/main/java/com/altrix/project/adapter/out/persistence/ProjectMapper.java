package com.altrix.project.adapter.out.persistence;

import com.altrix.project.domain.model.Project;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Maps between the domain model {@link Project} and the JPA entity
 * {@link ProjectJpaEntity}.
 *
 * <p>Keeping mapping logic in a dedicated class follows SRP —
 * neither the domain model nor the JPA entity carries conversion logic.
 */
@Component
class ProjectMapper {

    Project toDomain(ProjectJpaEntity entity) {
        return Project.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .name(entity.getName())
                .storageKey(entity.getStorageKey())
                .status(entity.getStatus())
                .buildSystem(entity.getBuildSystem())
                .configFormat(entity.getConfigFormat())
                .framework(entity.getFramework())
                .configFormatPreference(entity.getConfigFormatPreference())
                .jakartaMessagingTarget(entity.getJakartaMessagingTarget())
                .eligibleForMigration(entity.isEligibleForMigration())
                .detectedTechnologies(parseTechnologies(entity.getDetectedTechnologies()))
                .repoUrl(entity.getRepoUrl())
                .trackedBranch(entity.getTrackedBranch())
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    ProjectJpaEntity toJpaEntity(Project domain) {
        return ProjectJpaEntity.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .name(domain.getName())
                .storageKey(domain.getStorageKey())
                .status(domain.getStatus())
                .buildSystem(domain.getBuildSystem())
                .configFormat(domain.getConfigFormat())
                .framework(domain.getFramework())
                .configFormatPreference(domain.getConfigFormatPreference())
                .jakartaMessagingTarget(domain.getJakartaMessagingTarget())
                .eligibleForMigration(domain.isEligibleForMigration())
                .detectedTechnologies(joinTechnologies(domain.getDetectedTechnologies()))
                .repoUrl(domain.getRepoUrl())
                .trackedBranch(domain.getTrackedBranch())
                .source(domain.getSource())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    private static List<String> parseTechnologies(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.asList(csv.split(","));
    }

    private static String joinTechnologies(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return String.join(",", list);
    }
}
