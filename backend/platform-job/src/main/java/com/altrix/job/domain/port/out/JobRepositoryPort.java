package com.altrix.job.domain.port.out;

import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.JobFilter;

import java.util.List;
import java.util.Optional;

/**
 * Secondary port — persistence abstraction.
 * The domain never imports JPA or Hibernate.
 */
public interface JobRepositoryPort {

    MigrationJob save(MigrationJob job);

    Optional<MigrationJob> findById(String jobId);

    List<MigrationJob> findAll(JobFilter filter);
}
