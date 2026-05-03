package com.altrix.orchestrator.domain.port.in;

import com.altrix.common.domain.model.ProjectContext;

/**
 * Primary port — single entry point into the orchestrator domain.
 *
 * <p>Receives an initial context (jobId + projectId only) and runs
 * all agents in sequence, returning the fully enriched context.
 */
public interface RunPipelineUseCase {

    /**
     * Executes the full agent pipeline synchronously.
     *
     * @param initial context with jobId and projectId populated
     * @return enriched context with all agent results
     */
    ProjectContext run(ProjectContext initial);
}
