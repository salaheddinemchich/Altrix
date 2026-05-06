package com.altrix.job.domain.service;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.exception.JobNotFoundException;
import com.altrix.job.domain.model.JobProviderProfile;
import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.CreateJobUseCase;
import com.altrix.job.domain.port.in.UpdateJobStatusUseCase;
import com.altrix.job.domain.port.out.JobCachePort;
import com.altrix.job.domain.port.out.JobEventPublisherPort;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class JobCommandService implements CreateJobUseCase, UpdateJobStatusUseCase {

    private final JobRepositoryPort jobRepository;
    private final JobCachePort jobCachePort;
    private final JobEventPublisherPort jobEventPublisher;

    @Override
    public MigrationJob createJob(
            String projectId,
            String userId,
            String projectStorageKey,
            ConfigFormatPreference configFormatPreference,
            JobProviderProfile providerProfile
    ) {
        log.info("Creating job for project '{}' user '{}' providerProfile={}",
                projectId, userId, providerProfile);
        MigrationJob job = MigrationJob.create(
                projectId, userId, projectStorageKey, configFormatPreference, providerProfile);
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
