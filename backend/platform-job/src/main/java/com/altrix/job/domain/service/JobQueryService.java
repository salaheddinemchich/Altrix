package com.altrix.job.domain.service;

import com.altrix.common.exception.JobNotFoundException;
import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.GetJobQuery;
import com.altrix.job.domain.port.in.JobFilter;
import com.altrix.job.domain.port.out.JobCachePort;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * CQRS Query service — handles all read-only job operations.
 *
 * <p>Completely separate from {@link JobCommandService}.
 * Status reads are served from Redis cache — no DB hit unless cache misses.
 */
@Slf4j
@RequiredArgsConstructor
public class JobQueryService implements GetJobQuery {

    private final JobRepositoryPort jobRepository;
    private final JobCachePort jobCachePort;

    @Override
    public MigrationJob findById(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    @Override
    public String getStatus(String jobId) {
        return jobCachePort.getStatus(jobId)
                .orElseGet(() -> {
                    log.debug("Cache miss for job '{}' — reading from DB", jobId);
                    MigrationJob job = findById(jobId);
                    jobCachePort.putStatus(jobId, job.getStatus().name());
                    return job.getStatus().name();
                });
    }

    @Override
    public List<MigrationJob> findAll(JobFilter filter) {
        return jobRepository.findAll(filter);
    }
}
