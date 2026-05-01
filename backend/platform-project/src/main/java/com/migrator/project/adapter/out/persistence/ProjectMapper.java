package com.migrator.project.adapter.out.persistence;

import com.migrator.project.domain.model.Project;
import org.springframework.stereotype.Component;

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
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
