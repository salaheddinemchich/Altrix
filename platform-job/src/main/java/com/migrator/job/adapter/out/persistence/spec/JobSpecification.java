package com.migrator.job.adapter.out.persistence.spec;

import com.migrator.job.adapter.out.persistence.MigrationJobJpaEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria API Specification factory for {@link MigrationJobJpaEntity}.
 *
 * <p>Each static method returns a composable {@link Specification}.
 * The persistence adapter combines them using {@code .and()} based on
 * which fields in {@link JobFilter} are non-null.
 *
 * <p>This pattern avoids raw JPQL strings and keeps queries type-safe,
 * testable, and composable — following the Open/Closed Principle.
 */
public final class JobSpecification {

    private JobSpecification() {}

    /**
     * Builds a combined Specification from all non-null fields in the filter.
     */
    public static Specification<MigrationJobJpaEntity> from(JobFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.userId() != null) {
                predicates.add(cb.equal(root.get("userId"), filter.userId()));
            }
            if (filter.projectId() != null) {
                predicates.add(cb.equal(root.get("projectId"), filter.projectId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.createdAfter() != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), filter.createdAfter()));
            }
            if (filter.createdBefore() != null) {
                predicates.add(cb.lessThanOrEqualTo(
                        root.get("createdAt"), filter.createdBefore()));
            }

            // Default sort — newest first
            query.orderBy(cb.desc(root.get("createdAt")));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
