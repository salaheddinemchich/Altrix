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

/**
 * CQRS Command service — handles all state-changing job operations.
 *
 * <p>Pure Java. No Spring, no JPA, no Kafka, no Redis imported here.
 * All infrastructure concerns are hidden behind port interfaces.
 */
@Slf4j
@RequiredArgsConstructor
public class JobCommandService implements CreateJobUseCase, UpdateJobStatusUseCase {

    private final JobRepository    jobRepository;
    private final JobCachePort     jobCachePort;
    private final JobEventPublisher jobEventPublisher;

    // ── CreateJobUseCase ──────────────────────────────────────────────────────

    @Override
    public MigrationJob createJob(
            String projectId,
            String userId,
            ConfigFormatPreference configFormatPreference
    ) {
        log.info("Creating job for project '{}' user '{}'", projectId, userId);

        MigrationJob job = MigrationJob.create(projectId, userId, configFormatPreference);
        job = jobRepository.save(job);

        jobCachePort.putStatus(job.getId(), job.getStatus().name());
        jobEventPublisher.publishJobCreated(job);

        log.info("Job '{}' created in PENDING status", job.getId());
        return job;
    }

    // ── UpdateJobStatusUseCase ────────────────────────────────────────────────

    @Override
    public MigrationJob markAnalyzing(String jobId) {
        return updateAndPersist(jobId, job -> job.startAnalyzing());
    }

    @Override
    public MigrationJob markMigrating(String jobId) {
        return updateAndPersist(jobId, job -> job.startMigrating());
    }

    @Override
    public MigrationJob markDone(String jobId, String outputStorageKey) {
        MigrationJob updated = loadOrThrow(jobId).complete(outputStorageKey);
        updated = jobRepository.save(updated);
        jobCachePort.putStatus(updated.getId(), updated.getStatus().name());
        jobEventPublisher.publishJobCompleted(updated);
        log.info("Job '{}' DONE — output at '{}'", jobId, outputStorageKey);
        return updated;
    }

    @Override
    public MigrationJob markFailed(String jobId, String reason) {
        MigrationJob updated = loadOrThrow(jobId).fail(reason);
        updated = jobRepository.save(updated);
        jobCachePort.putStatus(updated.getId(), updated.getStatus().name());
        jobEventPublisher.publishJobCompleted(updated);
        log.error("Job '{}' FAILED — reason: {}", jobId, reason);
        return updated;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MigrationJob updateAndPersist(
            String jobId,
            java.util.function.UnaryOperator<MigrationJob> transition
    ) {
        MigrationJob updated = transition.apply(loadOrThrow(jobId));
        updated = jobRepository.save(updated);
        jobCachePort.putStatus(updated.getId(), updated.getStatus().name());
        log.debug("Job '{}' → {}", jobId, updated.getStatus());
        return updated;
    }

    private MigrationJob loadOrThrow(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }
}
