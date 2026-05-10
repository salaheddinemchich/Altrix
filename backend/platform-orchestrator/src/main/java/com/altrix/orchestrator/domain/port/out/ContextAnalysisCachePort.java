package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.AnalysisReport;

import java.util.Optional;

/**
 * Driven port — caches {@link AnalysisReport} produced by Agent 1 to avoid
 * redundant AI calls on repeated runs of an unchanged codebase (#154).
 *
 * <p>Cache key is derived by the caller as {@code SHA-256(sortedFilePaths + contents)}.
 * Implementations may add an extra TTL or eviction strategy on top.
 */
public interface ContextAnalysisCachePort {

    /**
     * Returns a cached report for the given content hash, or empty on miss.
     */
    Optional<AnalysisReport> get(String contentHash);

    /**
     * Stores a report under the given content hash.
     * Implementations must treat this as best-effort — never throw.
     */
    void put(String contentHash, AnalysisReport report);

    /**
     * Removes the entry for the given hash (used for forced refresh / cache bypass).
     */
    void evict(String contentHash);
}
