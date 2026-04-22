package com.migrator.job.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository.
 * Extends {@link JpaSpecificationExecutor} to enable Criteria API queries.
 */
@Repository
interface MigrationJobJpaRepository
        extends JpaRepository<MigrationJobJpaEntity, String>,
                JpaSpecificationExecutor<MigrationJobJpaEntity> {
}
