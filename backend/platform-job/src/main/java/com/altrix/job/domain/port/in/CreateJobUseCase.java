package com.altrix.job.domain.port.in;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.job.domain.model.JobProviderProfile;
import com.altrix.job.domain.model.MigrationJob;

public interface CreateJobUseCase {

    MigrationJob createJob(
            String projectId,
            String userId,
            String projectStorageKey,
            ConfigFormatPreference configFormatPreference,
            JobProviderProfile providerProfile,
            JakartaMessagingTarget jakartaMessagingTarget
    );
}
