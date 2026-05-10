package com.altrix.orchestrator.domain.model;

import java.util.Map;

/**
 * Result of {@link com.altrix.orchestrator.infrastructure.ai.ContextPruner} (#27).
 *
 * <p>Wraps the filtered file map and records pruning metrics for observability.
 *
 * @param files     The pruned subset of source files to pass to Agent 3.
 * @param totalFiles Total number of files in the original source set.
 * @param prunedFiles Number of files excluded by the pruner (totalFiles - files.size()).
 */
public record PrunedContext(
        Map<String, String> files,
        int totalFiles,
        int prunedFiles
) {
    public PrunedContext {
        files = files != null ? Map.copyOf(files) : Map.of();
    }

    public int includedFiles() {
        return files.size();
    }

    public double pruneRatio() {
        return totalFiles == 0 ? 0.0 : (double) prunedFiles / totalFiles;
    }
}
