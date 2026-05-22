package com.altrix.orchestrator.adapter.in.rest.dto;

import java.time.Instant;

/**
 * One row of the approval-history timeline (#126).
 *
 * <p>The current schema records exactly one decision per session (the
 * workflow doesn't yet support re-approval after a plan edit), so the
 * history endpoint returns a single-element list when a decision exists and
 * an empty list otherwise.  Returning a list keeps the contract stable for
 * the future when re-approval is added.
 *
 * @param decidedBy    identity of the reviewer (JWT sub claim).
 * @param decidedAt    when the decision was recorded.
 * @param decisionKind {@code APPROVED} or {@code REJECTED}.
 * @param reason       human-readable rejection reason — null on APPROVED.
 */
public record ApprovalHistoryEntryResponse(
        String decidedBy,
        Instant decidedAt,
        String decisionKind,
        String reason
) {}
