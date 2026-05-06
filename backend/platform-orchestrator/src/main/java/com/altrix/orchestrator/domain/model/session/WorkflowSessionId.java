package com.altrix.orchestrator.domain.model.session;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed identity value object for {@link WorkflowSession}.
 * Wraps a UUID so session IDs can never be confused with job or project IDs.
 */
public record WorkflowSessionId(UUID value) {

    public WorkflowSessionId {
        Objects.requireNonNull(value, "WorkflowSessionId value must not be null");
    }

    public static WorkflowSessionId generate() {
        return new WorkflowSessionId(UUID.randomUUID());
    }

    public static WorkflowSessionId of(UUID value) {
        return new WorkflowSessionId(value);
    }

    public static WorkflowSessionId of(String value) {
        return new WorkflowSessionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
