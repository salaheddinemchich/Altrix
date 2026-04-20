package com.migrator.project.domain.port.in;

import com.migrator.common.domain.model.MigrationJobRecord;
import java.util.UUID;
import java.util.List;

public interface ManageMigrationJobUseCase {
    MigrationJobRecord createJob(MigrationJobRecord job);
    MigrationJobRecord getJob(UUID jobId);
    List<MigrationJobRecord> getAllJobs();
}
