package com.migrator.common.exception;

/**
 * Thrown when an AI agent fails to complete its task.
 *
 * <p>This is a non-recoverable failure — the migration job will transition
 * to {@code FAILED} status when this exception is thrown.
 */
public final class AgentFailureException extends BasePlatformException {

    private static final String ERROR_CODE = "AGENT_FAILURE";

    /**
     * @param agentName  human-readable name of the agent that failed, e.g. "Architecture Analyzer"
     * @param reason     short description of why it failed
     */
    public AgentFailureException(String agentName, String reason) {
        super("Agent '" + agentName + "' failed: " + reason, ERROR_CODE);
    }
}
