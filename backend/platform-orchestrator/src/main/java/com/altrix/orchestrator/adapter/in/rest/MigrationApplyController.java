package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.ApplyMigrationRequest;
import com.altrix.orchestrator.adapter.in.rest.dto.BranchStrategyRequest;
import com.altrix.orchestrator.adapter.in.rest.dto.BranchStrategyResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.MigrationApplyResultResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.RepositoryAccessResponse;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.CancelMigrationApplyUseCase;
import com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase;
import com.altrix.orchestrator.domain.port.in.ChooseBranchStrategyUseCase;
import com.altrix.orchestrator.domain.port.in.ConfirmAndApplyMigrationUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Migration Approval & Branch Strategy Workflow REST surface.
 *
 * <p>All endpoints live under {@code /api/v1/migration-apply/} so there
 * is no ambiguity with {@code ProjectIngestionController} (mapped to
 * {@code /api/v1/projects}) or {@code SessionController}
 * ({@code /api/v1/sessions}).  Earlier draft used
 * {@code /api/v1/projects/.../repo-access} which collided with the
 * projects namespace and triggered an OAuth2 login redirect under some
 * routing conditions.
 *
 * <p>Class-level {@code @PreAuthorize("isAuthenticated()")} mirrors
 * {@link SessionController} so the security filter chain sees the same
 * shape for both controllers — Spring Security's default 401 entry
 * point (configured in {@code SecurityConfig}) fires for unauthenticated
 * calls, not the OAuth2 login redirect.
 *
 * <p>The {@code @ExceptionHandler}s below translate the few
 * domain exceptions this controller throws into clean JSON 4xx
 * responses so the frontend can render specific messages instead of
 * "Failed to fetch".
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/migration-apply")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MigrationApplyController {

    private final CheckRepositoryAccessUseCase accessUseCase;
    private final ChooseBranchStrategyUseCase chooseStrategy;
    private final ConfirmAndApplyMigrationUseCase applyUseCase;
    private final CancelMigrationApplyUseCase cancelUseCase;

    /**
     * GET /api/v1/migration-apply/sessions/{sessionId}/access
     *
     * <p>Returns what the authenticated user can do on the session's
     * project repository — drives the strategy-picker UI.  Uses
     * sessionId rather than projectId because the frontend is on the
     * job-detail page and already knows the session.
     */
    @GetMapping("/sessions/{sessionId}/access")
    public ResponseEntity<RepositoryAccessResponse> getAccess(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String actorUserId) {
        RepositoryAccess access = accessUseCase.checkForSession(
                WorkflowSessionId.of(sessionId), actorUserId);
        return ResponseEntity.ok(RepositoryAccessResponse.from(access));
    }

    /**
     * POST /api/v1/migration-apply/sessions/{sessionId}/strategy
     *
     * <p>User picks the strategy + (optional) branch name + (optional)
     * commit/PR metadata.  Server validates against the user's
     * permissions, persists the choice, returns a single-use
     * {@code confirmationToken}.
     */
    @PostMapping("/sessions/{sessionId}/strategy")
    public ResponseEntity<BranchStrategyResponse> setStrategy(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String actorUserId,
            @Valid @RequestBody BranchStrategyRequest body) {
        var outcome = chooseStrategy.choose(
                WorkflowSessionId.of(sessionId),
                body.strategy(),
                body.branchName(),
                body.commitMessage(),
                body.prTitle(),
                body.prBody(),
                actorUserId);
        return ResponseEntity.ok(BranchStrategyResponse.from(outcome));
    }

    /**
     * POST /api/v1/migration-apply/sessions/{sessionId}/confirm
     *
     * <p>Final confirmation gate.  Re-validates the confirmation token
     * + that {@code userApproved} is true; the actual apply (commit /
     * push / PR) runs server-side using strategy values loaded from
     * the persisted choose-time row, not the request body.
     */
    @PostMapping("/sessions/{sessionId}/confirm")
    public ResponseEntity<MigrationApplyResultResponse> confirm(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String actorUserId,
            @Valid @RequestBody ApplyMigrationRequest body) {
        MigrationApplyResult result = applyUseCase.apply(new ConfirmAndApplyMigrationUseCase.Request(
                WorkflowSessionId.of(sessionId),
                body.strategy(),
                body.branchName(),
                body.commitMessage(),
                body.prTitle(),
                body.prBody(),
                body.confirmationToken(),
                body.userApproved(),
                actorUserId));
        return ResponseEntity.ok(MigrationApplyResultResponse.from(result));
    }

    /**
     * POST /api/v1/migration-apply/sessions/{sessionId}/cancel
     *
     * <p>User backed out at the confirmation gate.  No repository
     * action is taken; the audit log records the cancellation.  The
     * session stays in DONE so the user can pick a different strategy.
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<MigrationApplyResultResponse> cancel(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String actorUserId) {
        MigrationApplyResult result = cancelUseCase.cancel(WorkflowSessionId.of(sessionId), actorUserId);
        return ResponseEntity.ok(MigrationApplyResultResponse.from(result));
    }

    // ── exception translation ──────────────────────────────────────────────

    /**
     * Turn the domain-layer exceptions this controller can throw into
     * actionable 4xx JSON responses.  Without this advice,
     * IllegalStateException bubbles to Spring's default handler as a
     * 500, which the frontend renders as a useless "Failed to fetch"
     * red bar.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        log.debug("migration-apply 400 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", safeMessage(e)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        log.debug("migration-apply 409 — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", safeMessage(e)));
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
