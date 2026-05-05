package com.altrix.orchestrator.adapter.out.websocket;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Status of a single agent step, serialised as a lowercase string in the WebSocket contract.
 */
public enum WorkflowStatus {
    RUNNING, DONE, FAILED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
