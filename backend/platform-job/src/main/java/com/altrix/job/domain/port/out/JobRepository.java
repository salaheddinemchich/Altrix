package com.altrix.job.domain.port.out;

import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.adapter.out.persistence.spec.JobFilter;

import java.util.List;
import java.util.Optional;

/**
 * Secondary port — persistence abstraction.
 * The domain never imports JPA or Hibernate.
 */
public interface JobRepository {

    MigrationJob save(MigrationJob job);

    Optional<MigrationJob> findById(String jobId);

    List<MigrationJob> findAll(JobFilter filter);
}
