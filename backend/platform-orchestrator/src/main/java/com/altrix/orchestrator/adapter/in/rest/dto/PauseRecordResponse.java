package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.session.SessionPauseRecord;

import java.time.Instant;

public record PauseRecordResponse(
        Long id,
        String pausedFrom,
        Instant pausedAt,
        Instant resumedAt
) {
    public static PauseRecordResponse from(SessionPauseRecord r) {
        return new PauseRecordResponse(
                r.id(),
                r.pausedFrom().name(),
                r.pausedAt(),
                r.resumedAt());
    }
}
