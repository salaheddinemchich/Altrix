package com.migrator.job.adapter.in.rest;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.common.domain.enums.JobStatus;
import com.migrator.job.domain.model.MigrationJob;

import java.time.Instant;

public record JobResponse(
        String                 id,
        String                 projectId,
        String                 userId,
        JobStatus              status,
        ConfigFormatPreference configFormatPreference,
        String                 outputStorageKey,
        String                 errorMessage,
        Instant                createdAt,
        Instant                completedAt
) {
    public static JobResponse from(MigrationJob job) {
        return new JobResponse(
                job.getId(),
                job.getProjectId(),
                job.getUserId(),
                job.getStatus(),
                job.getConfigFormatPreference(),
                job.getOutputStorageKey(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }
}
