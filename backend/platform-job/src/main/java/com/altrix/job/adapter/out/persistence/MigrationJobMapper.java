package com.altrix.job.adapter.out.persistence;

import com.altrix.job.domain.model.MigrationJob;
import org.springframework.stereotype.Component;

@Component
class MigrationJobMapper {

    MigrationJob toDomain(MigrationJobJpaEntity e) {
        return MigrationJob.builder()
                .id(e.getId())
                .projectId(e.getProjectId())
                .userId(e.getUserId())
                .projectStorageKey(e.getProjectStorageKey())
                .status(e.getStatus())
                .configFormatPreference(e.getConfigFormatPreference())
                .providerProfile(e.getProviderProfile())
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
                .projectStorageKey(j.getProjectStorageKey())
                .status(j.getStatus())
                .configFormatPreference(j.getConfigFormatPreference())
                .providerProfile(j.getProviderProfile())
                .outputStorageKey(j.getOutputStorageKey())
                .errorMessage(j.getErrorMessage())
                .createdAt(j.getCreatedAt())
                .updatedAt(j.getUpdatedAt())
                .completedAt(j.getCompletedAt())
                .build();
    }
}
