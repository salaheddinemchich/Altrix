package com.migrator.job.domain.port.in;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.job.domain.model.MigrationJob;

/**
 * Primary port — CQRS Command side.
 * Creates a new migration job for a registered project.
 */
public interface CreateJobUseCase {

    /**
     * @param projectId              the registered project to migrate
     * @param userId                 owner of the job
     * @param configFormatPreference output config format preference
     * @return the newly created job in PENDING status
     */
    MigrationJob createJob(
            String projectId,
            String userId,
            ConfigFormatPreference configFormatPreference
    );
}
