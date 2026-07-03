package com.altrix.job.adapter.in.rest;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.common.domain.enums.JobStatus;
import com.altrix.job.domain.model.JobProviderProfile;
import com.altrix.job.domain.model.MigrationJob;

import java.time.Instant;

public record JobResponse(
        String                 id,
        String                 projectId,
        String                 userId,
        JobStatus              status,
        ConfigFormatPreference configFormatPreference,
        JobProviderProfile     providerProfile,
        JakartaMessagingTarget jakartaMessagingTarget,
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
                job.getProviderProfile(),
                job.getJakartaMessagingTarget(),
                job.getOutputStorageKey(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }
}
