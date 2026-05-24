package com.altrix.orchestrator.domain.model.sandbox;

/**
 * Thread-local context propagated from {@code ResumeMigrationService}
 * down into the {@code SandboxRunnerPort} implementations (#105).
 *
 * <p>The {@code SandboxValidatorAgent}'s {@code MigrationAgent<MigrationArtifact,
 * ValidationReport>} contract is fixed — it can't grow extra parameters
 * without rippling through the agent pipeline.  But the Docker runners
 * need the sessionId to persist their captured logs.  ThreadLocal is
 * the standard escape hatch for "context that flows through a call
 * stack without changing signatures".
 *
 * <p>Caller MUST clear in a finally block.  Runners read; they never
 * set.
 */
public final class SandboxContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private SandboxContext() {}

    /** Sets the current session id — call from the validator's caller before {@code validator.execute()}. */
    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    /** Returns the current session id, or null when the context isn't initialised (tests, stray threads). */
    public static String currentSessionId() {
        return SESSION_ID.get();
    }

    /** MUST be called in a finally block by whoever set the context. */
    public static void clear() {
        SESSION_ID.remove();
    }
}
