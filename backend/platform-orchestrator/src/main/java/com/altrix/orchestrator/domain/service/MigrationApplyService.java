package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.model.apply.BranchFileChange;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry.EventType;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyDecision;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyOutcome;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.CancelMigrationApplyUseCase;
import com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase;
import com.altrix.orchestrator.domain.port.in.ConfirmAndApplyMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort.State;
import com.altrix.orchestrator.domain.port.out.OAuthTokenLookupPort;
import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort;
import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort.ProjectMetadata;
import com.altrix.orchestrator.domain.port.out.RepositoryProviderPort;
import com.altrix.orchestrator.infrastructure.apply.BranchStrategyHandler;
import com.altrix.orchestrator.infrastructure.apply.BranchStrategyHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Steps 4 + 5: final confirmation gate + cancellation.  Re-validates
 * the user's decision against the stored strategy + token + permissions
 * before dispatching to the {@link BranchStrategyHandler} that owns
 * the chosen branch path.
 */
@Slf4j
@RequiredArgsConstructor
public class MigrationApplyService
        implements ConfirmAndApplyMigrationUseCase, CancelMigrationApplyUseCase {

    private final MigrationApplyStatePort applyState;
    private final MigrationApplyAuditLogPort auditLog;
    private final ProjectMetadataLookupPort projects;
    private final OAuthTokenLookupPort tokens;
    private final CheckRepositoryAccessUseCase accessUseCase;
    private final BranchStrategyHandlerRegistry handlers;

    @Override
    public MigrationApplyResult apply(Request request) {
        WorkflowSessionId sessionId = request.sessionId();
        if (!request.userApproved()) {
            throw new IllegalStateException("User approval flag must be true.");
        }
        State state = applyState.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        validateConfirmation(state, request);

        ProjectMetadata project = projects.findById(state.projectId())
                .orElseThrow(() -> new IllegalStateException("Project metadata missing for " + state.projectId()));
        String accessToken = tokens.findAccessToken(request.actorUserId(), project.providerId())
                .orElseThrow(() -> new IllegalStateException("No OAuth token for actor"));

        // Re-check permissions at apply time — between choose and apply the
        // user could have lost access (token revoked, team membership changed).
        RepositoryAccess access = accessUseCase.check(state.projectId(), request.actorUserId());
        if (!access.availableStrategies().contains(state.strategy())) {
            applyState.saveOutcome(sessionId, MigrationApplyOutcome.FAILED, null, null, Instant.now());
            return MigrationApplyResult.failed("Strategy " + state.strategy() + " no longer permitted.");
        }

        // Decision is built from STORED state (the trusted truth), never the request.
        MigrationApplyDecision decision = new MigrationApplyDecision(
                sessionId,
                state.strategy(),
                state.branchName(),
                state.baseBranch(),
                state.commitMessage(),
                state.prTitle(),
                state.prBody(),
                state.confirmationToken(),
                request.actorUserId(),
                true);

        auditLog.save(new MigrationApplyAuditEntry(
                sessionId, request.actorUserId(), EventType.APPLY_CONFIRMED,
                Map.of("strategy", state.strategy().name(),
                       "branchName", state.branchName() == null ? "" : state.branchName()),
                Instant.now()));

        List<BranchFileChange> changes = toBranchChanges(state.migratedFiles());
        BranchStrategyHandler handler = handlers.handlerFor(state.strategy());

        try {
            MigrationApplyResult result =
                    handler.apply(decision, accessToken, project.repoFullName(), changes);
            applyState.saveOutcome(sessionId, result.outcome(),
                    result.prUrl(), result.commitSha(), result.completedAt());
            auditLog.save(new MigrationApplyAuditEntry(
                    sessionId, request.actorUserId(), eventTypeFor(result.outcome()),
                    Map.of(
                            "outcome",    result.outcome().name(),
                            "branchName", n(result.branchName()),
                            "prUrl",      n(result.prUrl()),
                            "commitSha",  n(result.commitSha()),
                            "mergeSha",   n(result.mergeSha())
                    ),
                    Instant.now()));
            return result;
        } catch (RepositoryProviderPort.RepositoryOperationException e) {
            applyState.saveOutcome(sessionId, MigrationApplyOutcome.FAILED, null, null, Instant.now());
            auditLog.save(new MigrationApplyAuditEntry(
                    sessionId, request.actorUserId(), EventType.FAILED,
                    Map.of("error", e.getMessage()), Instant.now()));
            return MigrationApplyResult.failed(e.getMessage());
        } catch (Exception e) {
            applyState.saveOutcome(sessionId, MigrationApplyOutcome.FAILED, null, null, Instant.now());
            log.error("Apply failed for session={} actor={}: {}", sessionId, request.actorUserId(), e.getMessage(), e);
            auditLog.save(new MigrationApplyAuditEntry(
                    sessionId, request.actorUserId(), EventType.FAILED,
                    Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()), Instant.now()));
            return MigrationApplyResult.failed("Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public MigrationApplyResult cancel(WorkflowSessionId sessionId, String actorUserId) {
        applyState.markCancelled(sessionId, Instant.now());
        auditLog.save(new MigrationApplyAuditEntry(
                sessionId, actorUserId, EventType.CANCELLED, Map.of(), Instant.now()));
        return MigrationApplyResult.cancelled();
    }

    // ── validation ──────────────────────────────────────────────────────────

    /**
     * Server-side validation of the user's apply request.  Throws on any
     * mismatch so the caller surfaces a 4xx — no apply happens.
     */
    private void validateConfirmation(State state, Request request) {
        if (!"DONE".equals(state.sessionStatus())) {
            throw new IllegalStateException("Session not in DONE state.");
        }
        if (!"STRATEGY_SET".equals(state.applyStatus())) {
            throw new IllegalStateException("No pending strategy on this session — choose strategy first.");
        }
        if (state.confirmationToken() == null
                || !state.confirmationToken().equals(request.confirmationToken())) {
            throw new IllegalStateException("Confirmation token does not match.");
        }
        if (state.confirmationExpires() != null && Instant.now().isAfter(state.confirmationExpires())) {
            throw new IllegalStateException("Confirmation token expired — re-choose the strategy.");
        }
        if (request.strategy() != state.strategy()) {
            throw new IllegalStateException("Strategy mismatch — token was issued for " + state.strategy());
        }
        if (request.strategy() != com.altrix.orchestrator.domain.model.apply.BranchStrategy.DIRECT_MERGE
                && state.branchName() != null
                && request.branchName() != null
                && !state.branchName().equals(request.branchName())) {
            throw new IllegalStateException("Target branch differs from the one set at choose time.");
        }
    }

    /** Maps the persisted migration output to provider-agnostic file changes. */
    private static List<BranchFileChange> toBranchChanges(List<MigratedFile> files) {
        List<BranchFileChange> out = new ArrayList<>(files.size());
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path == null || path.isBlank()) continue;
            if (f.changeType() == FileChangeType.DELETED) {
                out.add(BranchFileChange.delete(path));
                continue;
            }
            // UNCHANGED files are pushed too — the result must mirror the
            // entire project tree, not just the diff.  This keeps the new
            // branch / PR self-contained and reviewable in one place.
            String content = f.content() == null ? "" : f.content();
            out.add(BranchFileChange.upsert(path, content.getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static String n(Object v) {
        return v == null ? "" : v.toString();
    }

    private static EventType eventTypeFor(MigrationApplyOutcome outcome) {
        return switch (outcome) {
            case BRANCH_CREATED  -> EventType.BRANCH_CREATED;
            case PR_CREATED      -> EventType.PR_CREATED;
            case MERGED_TO_MAIN  -> EventType.MERGED;
            case CANCELLED       -> EventType.CANCELLED;
            case FAILED          -> EventType.FAILED;
        };
    }
}
