package com.migrator.job.domain.port.in;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.job.domain.model.MigrationJob;

public interface CreateJobUseCase {

    MigrationJob createJob(
            String projectId,
            String userId,
            String projectStorageKey,
            ConfigFormatPreference configFormatPreference
    );
}
