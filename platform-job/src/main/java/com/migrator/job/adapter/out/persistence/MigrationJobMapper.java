package com.migrator.job.adapter.out.persistence;

import com.migrator.job.domain.model.MigrationJob;
import org.springframework.stereotype.Component;

@Component
class MigrationJobMapper {

    MigrationJob toDomain(MigrationJobJpaEntity e) {
        return MigrationJob.builder()
                .id(e.getId())
                .projectId(e.getProjectId())
                .userId(e.getUserId())
                .status(e.getStatus())
                .configFormatPreference(e.getConfigFormatPreference())
                .outputStorageKey(e.getOutputStorageKey())
                .errorMessage(e.getErrorMessage())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .completedAt(e.getCompletedAt())
                .build();
    }

    MigrationJobJpaEntity toJpaEntity(MigrationJob j) {
        return MigrationJobJpaEntity.builder()
                .id(j.getId())
                .projectId(j.getProjectId())
                .userId(j.getUserId())
                .status(j.getStatus())
                .configFormatPreference(j.getConfigFormatPreference())
                .outputStorageKey(j.getOutputStorageKey())
                .errorMessage(j.getErrorMessage())
                .createdAt(j.getCreatedAt())
                .updatedAt(j.getUpdatedAt())
                .completedAt(j.getCompletedAt())
                .build();
    }
}
