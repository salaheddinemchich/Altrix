package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigratedFile;

import java.util.List;
import java.util.Optional;

/**
 * Driven port — stores and retrieves the most recent successful migration output
 * per project so OrchestratorService can serve it when AI providers are unavailable.
 */
public interface MigrationPlanCachePort {
    /**
     * Store the result of a successful migration run for a given project + target stack.
     */
    void store(String projectId, String targetStack, List<MigratedFile> files);

    /**
     * Return the most recent cached plan, or empty when no cache entry exists.
     */
    Optional<List<MigratedFile>> loadLatest(String projectId, String targetStack);
}
