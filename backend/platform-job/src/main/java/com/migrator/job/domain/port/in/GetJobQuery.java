package com.migrator.job.domain.port.in;

import com.migrator.job.domain.model.MigrationJob;
import com.migrator.job.adapter.out.persistence.spec.JobFilter;

import java.util.List;

/**
 * Primary port — CQRS Query side.
 * All read operations for jobs. Completely separate from command ports.
 */
public interface GetJobQuery {

    /** Find a specific job by ID. Throws JobNotFoundException if missing. */
    MigrationJob findById(String jobId);

    /** Fast status read — served from Redis cache, falls back to DB. */
    String getStatus(String jobId);

    /** Filtered list using Criteria API Specifications. */
    List<MigrationJob> findAll(JobFilter filter);
}
