package com.migrator.project.domain.port.out;

import com.migrator.common.domain.model.MigrationJobRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationJobRepositoryPort {
    MigrationJobRecord save(MigrationJobRecord job);
    Optional<MigrationJobRecord> findById(UUID jobId);
    List<MigrationJobRecord> findAll();
    void deleteById(UUID jobId);
}
