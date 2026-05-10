package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.session.SessionPage;

import java.util.List;

public record SessionPageResponse(
        List<SessionStatusResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static SessionPageResponse from(SessionPage domain) {
        return new SessionPageResponse(
                domain.content().stream().map(SessionStatusResponse::from).toList(),
                domain.page(),
                domain.size(),
                domain.totalElements(),
                domain.totalPages()
        );
    }
}
