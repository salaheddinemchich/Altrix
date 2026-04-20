package com.migrator.project.service;

import com.migrator.common.domain.enums.JobStatus;
import com.migrator.common.domain.model.MigrationJobRecord;
import com.migrator.project.domain.port.in.ManageMigrationJobUseCase;
import com.migrator.project.domain.port.out.MigrationJobRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MigrationJobService implements ManageMigrationJobUseCase {

    // Injecting the INTERFACE, not the JPA Adapter. 
    // This keeps the business logic decoupled from the database!
    private final MigrationJobRepositoryPort repositoryPort;

    @Override
    public MigrationJobRecord createJob(MigrationJobRecord job) {
        // Business Rule: New jobs always start as PENDING
        job.setStatus(JobStatus.PENDING); 
        return repositoryPort.save(job);
    }

    @Override
    public MigrationJobRecord getJob(UUID jobId) {
        return repositoryPort.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Migration Job not found with ID: " + jobId));
    }

    @Override
    public List<MigrationJobRecord> getAllJobs() {
        return repositoryPort.findAll();
    }
}
