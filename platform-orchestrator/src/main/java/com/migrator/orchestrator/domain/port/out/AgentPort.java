package com.migrator.orchestrator.domain.port.out;

import com.migrator.common.domain.model.ProjectContext;

/**
 * Secondary port — contract every AI agent must implement.
 *
 * <p>Each agent receives the current immutable context and returns
 * a new enriched copy. Agents never mutate the context directly.
 *
 * <p>Ordering is determined by {@link #getOrder()} — lower = runs first.
 */
public interface AgentPort {

    /** Human-readable name used in logs and progress events. */
    String getName();

    /** Execution order — Agent 1 = 1, Agent 3 = 3. */
    int getOrder();

    /**
     * Executes this agent's task and returns an enriched context.
     *
     * @param context current pipeline state
     * @return new context with this agent's findings added
     * @throws com.migrator.common.exception.AgentFailureException on non-recoverable failure
     */
    ProjectContext execute(ProjectContext context);
}
