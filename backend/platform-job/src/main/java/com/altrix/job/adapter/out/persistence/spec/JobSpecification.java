package com.altrix.job.adapter.out.persistence.spec;

import com.altrix.job.adapter.out.persistence.MigrationJobJpaEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class JobSpecification {

    private JobSpecification() {}

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

            // Guard: query is null in count queries — only apply orderBy for select queries
            if (query != null && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                query.orderBy(cb.desc(root.get("createdAt")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
