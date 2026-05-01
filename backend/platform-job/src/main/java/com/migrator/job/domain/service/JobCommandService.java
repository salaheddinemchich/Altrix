package com.migrator.job.domain.service;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.common.exception.JobNotFoundException;
import com.migrator.job.domain.model.MigrationJob;
import com.migrator.job.domain.port.in.CreateJobUseCase;
import com.migrator.job.domain.port.in.UpdateJobStatusUseCase;
import com.migrator.job.domain.port.out.JobCachePort;
import com.migrator.job.domain.port.out.JobEventPublisher;
import com.migrator.job.domain.port.out.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class JobCommandService implements CreateJobUseCase, UpdateJobStatusUseCase {

    private final JobRepository     jobRepository;
    private final JobCachePort      jobCachePort;
    private final JobEventPublisher jobEventPublisher;

    @Override
    public MigrationJob createJob(
            String projectId,
            String userId,
            String projectStorageKey,
            ConfigFormatPreference configFormatPreference
    ) {
        log.info("Creating job for project '{}' user '{}'", projectId, userId);
        MigrationJob job = MigrationJob.create(projectId, userId, projectStorageKey, configFormatPreference);
        job = jobRepository.save(job);
        jobCachePort.putStatus(job.getId(), job.getStatus().name());
        jobEventPublisher.publishJobCreated(job);
        log.info("Job '{}' created in PENDING status", job.getId());
        return job;
    }

    @Override
    public MigrationJob markAnalyzing(String jobId) {
        return updateAndPersist(jobId, MigrationJob::startAnalyzing);
    }

    @Override
    public MigrationJob markMigrating(String jobId) {
        return updateAndPersist(jobId, MigrationJob::startMigrating);
    }

    @Override
    public MigrationJob markDone(String jobId, String outputStorageKey) {
        MigrationJob updated = loadOrThrow(jobId).complete(outputStorageKey);
        updated = jobRepository.save(updated);
        jobCachePort.putStatus(updated.getId(), updated.getStatus().name());
        jobEventPublisher.publishJobCompleted(updated);
        return updated;
    }

    @Override
    public MigrationJob markFailed(String jobId, String reason) {
        MigrationJob updated = loadOrThrow(jobId).fail(reason);
        updated = jobRepository.save(updated);
        jobCachePort.putStatus(updated.getId(), updated.getStatus().name());
        jobEventPublisher.publishJobCompleted(updated);
        return updated;
    }

    private MigrationJob updateAndPersist(
            String jobId,
            java.util.function.UnaryOperator<MigrationJob> transition
    ) {
        MigrationJob updated = transition.apply(loadOrThrow(jobId));
        updated = jobRepository.save(updated);
        jobCachePort.putStatus(updated.getId(), updated.getStatus().name());
        return updated;
    }

    private MigrationJob loadOrThrow(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }
}
