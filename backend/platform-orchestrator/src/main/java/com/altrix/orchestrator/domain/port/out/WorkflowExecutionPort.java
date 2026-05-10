package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.model.workflow.MigrationState;

/**
 * Secondary port — drives the five-agent migration pipeline.
 *
 * <p>The implementation ({@code MigrationWorkflowGraph}) is an infrastructure
 * concern; the domain service never imports LangGraph4j or any framework type.
 */
public interface WorkflowExecutionPort {

    /**
     * Runs the full typed agent pipeline for the given project context and
     * returns the final workflow state containing every agent's output.
     *
     * @param context project context produced by the Kafka listener
     * @return final {@link MigrationState} — callers can extract the migrated
     * files via {@code state.migrationArtifact()}
     */
    MigrationState execute(ProjectContext context);
}
