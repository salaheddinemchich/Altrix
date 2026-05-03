package com.altrix.common.domain.enums;

/**
 * Represents every possible state of a {@code MigrationJob} lifecycle.
 *
 * <p>State machine transitions (happy path):
 * <pre>
 *   PENDING → ANALYZING → MIGRATING → DONE
 * </pre>
 *
 * <p>Any state can transition to {@code FAILED} or {@code CANCELLED}.
 *
 * <ul>
 *   <li>{@code PENDING}   — job created, waiting for the orchestrator to pick it up</li>
 *   <li>{@code ANALYZING} — Agent 1 is reading source files and mapping PubSub components</li>
 *   <li>{@code MIGRATING} — Agent 3 is rewriting Java files and config to use Kafka</li>
 *   <li>{@code DONE}      — migrated ZIP is ready for download</li>
 *   <li>{@code FAILED}    — a non-recoverable error occurred; see job error message</li>
 *   <li>{@code CANCELLED} — user explicitly cancelled the job</li>
 * </ul>
 */
public enum JobStatus {

    PENDING,
    ANALYZING,
    MIGRATING,
    DONE,
    FAILED,
    CANCELLED;

    /** Returns true if this status represents a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /** Returns true if this job is currently being processed by an agent. */
    public boolean isInProgress() {
        return this == ANALYZING || this == MIGRATING;
    }
}
