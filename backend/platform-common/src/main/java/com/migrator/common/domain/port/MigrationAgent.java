package com.migrator.common.domain.port;

import com.migrator.common.exception.AgentFailureException;

/**
 * Typed pipeline agent contract.
 *
 * <p>Type parameters allow each agent to declare its precise input/output
 * so the compiler enforces the pipeline's data flow at each step.
 *
 * @param <I> the agent's input type
 * @param <O> the agent's output type
 */
public interface MigrationAgent<I, O> {

    /** Human-readable name used in logs and progress events. */
    String getName();

    /** Execution order in the pipeline — lower runs first. Gaps are intentional. */
    int getOrder();

    /**
     * Executes the agent's task.
     *
     * @param input agent-specific input
     * @return agent-specific output
     * @throws AgentFailureException on non-recoverable failure
     */
    O execute(I input);
}
