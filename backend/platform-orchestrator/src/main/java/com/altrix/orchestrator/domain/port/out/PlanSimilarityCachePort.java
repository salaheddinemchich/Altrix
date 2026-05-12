package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigrationPlan;

import java.util.Set;

/**
 * Driven port — stores and retrieves {@link MigrationPlan} objects keyed by
 * their dependency signature for semantic similarity lookups (#155).
 *
 * <p>The caller computes the Jaccard similarity via {@link
 * com.altrix.orchestrator.domain.service.PlanSimilarityService}; this port
 * only handles persistence.
 */
public interface PlanSimilarityCachePort {

    /**
     * Persists a plan alongside its dependency signature and Spring Boot major version.
     * Best-effort — implementations must never throw.
     *
     * @param depSignature  normalised set of dependency/integration names (lower-case)
     * @param springBootMajor extracted major version string, e.g. {@code "2"} or {@code "3"}; blank if unknown
     * @param plan          the plan to store
     */
    void store(Set<String> depSignature, String springBootMajor, MigrationPlan plan);

    /**
     * Returns all stored entries as a list so the caller can compute Jaccard
     * similarity and apply version filtering.
     */
    java.util.List<PlanSimilarityEntry> loadAll();

    /**
     * An entry in the similarity index.
     *
     * @param depSignature   dependency names stored when the plan was added
     * @param springBootMajor major version at storage time
     * @param plan            the stored migration plan
     */
    record PlanSimilarityEntry(
            java.util.List<String> depSignature,
            String springBootMajor,
            MigrationPlan plan
    ) {}
}
