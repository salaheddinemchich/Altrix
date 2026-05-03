package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;

/**
 * Secondary port — specialises {@link MigrationAgent} with {@link ProjectContext}
 * as both input and output, covering agents that enrich the shared pipeline state.
 *
 * <p>Ordering is determined by {@link #getOrder()} — lower = runs first.
 */
public interface AgentPort extends MigrationAgent<ProjectContext, ProjectContext> {
}
