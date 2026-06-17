package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry.EventType;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase;
import com.altrix.orchestrator.domain.port.in.ChooseBranchStrategyUseCase;
import com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort.State;
import com.altrix.orchestrator.infrastructure.config.MigrationApplyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Step 2: validate the user's chosen strategy against their
 * permissions, generate a one-shot confirmation token, persist the
 * choice, and emit an audit entry.  The token is what makes the
 * "server-validated confirmation" requirement enforceable — a client
 * cannot apply without one.
 */
@Slf4j
@RequiredArgsConstructor
public class BranchStrategyService implements ChooseBranchStrategyUseCase {

    private final CheckRepositoryAccessUseCase accessUseCase;
    private final MigrationApplyStatePort applyState;
    private final MigrationApplyAuditLogPort auditLog;
    private final MigrationApplyConfig config;

    @Override
    public Outcome choose(WorkflowSessionId sessionId,
                          BranchStrategy strategy,
                          String targetBranchName,
                          String commitMessage,
                          String prTitle,
                          String prBody,
                          String actorUserId) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId required");
        if (strategy == null) throw new IllegalArgumentException("strategy required");
        if (actorUserId == null) throw new IllegalArgumentException("actorUserId required");

        State state = applyState.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (!"DONE".equals(state.sessionStatus())) {
            throw new IllegalStateException("Cannot choose strategy on session in state " + state.sessionStatus()
                    + " — migration must finish first.");
        }
        if (state.migratedFiles().isEmpty()) {
            throw new IllegalStateException("Session " + sessionId + " has no migration output to apply.");
        }

        RepositoryAccess access = accessUseCase.check(state.projectId(), actorUserId);
        if (!access.availableStrategies().contains(strategy)) {
            throw new IllegalStateException(
                    "Strategy " + strategy + " not permitted for this user on " + access.fullName()
                            + " (permission=" + access.permission() + ")");
        }

        String resolvedBranchName = resolveBranchName(strategy, targetBranchName, sessionId);
        if (strategy != BranchStrategy.DIRECT_MERGE && !config.isValidBranchName(resolvedBranchName)) {
            throw new IllegalArgumentException(
                    "Branch name '" + resolvedBranchName + "' does not match required pattern.");
        }
        if (strategy != BranchStrategy.DIRECT_MERGE && resolvedBranchName.equals(access.defaultBranch())) {
            throw new IllegalArgumentException("Branch name must differ from default branch '"
                    + access.defaultBranch() + "'.");
        }

        String resolvedCommit = nonBlankOr(commitMessage, config.renderTemplate(config.commitMessageTemplate(), sessionId.value().toString()));
        String resolvedPrTitle = strategy == BranchStrategy.PULL_REQUEST
                ? nonBlankOr(prTitle, config.renderTemplate(config.prTitleTemplate(), sessionId.value().toString()))
                : null;
        String resolvedPrBody = strategy == BranchStrategy.PULL_REQUEST
                ? nonBlankOr(prBody, config.renderTemplate(config.prBodyTemplate(), sessionId.value().toString()))
                : null;

        UUID token = UUID.randomUUID();
        Instant expires = Instant.now().plus(config.confirmationTokenTtl());

        applyState.saveStrategyChoice(
                sessionId, strategy, resolvedBranchName, access.defaultBranch(),
                resolvedCommit, resolvedPrTitle, resolvedPrBody,
                token, expires, actorUserId);

        auditLog.save(new MigrationApplyAuditEntry(
                sessionId, actorUserId, EventType.STRATEGY_CHOSEN,
                Map.of(
                        "strategy", strategy.name(),
                        "branchName", resolvedBranchName == null ? "" : resolvedBranchName,
                        "baseBranch", access.defaultBranch(),
                        "repoFullName", access.fullName()
                ),
                Instant.now()));

        log.info("Strategy chosen: session={} strategy={} actor={}", sessionId, strategy, actorUserId);
        return new Outcome(token, strategy, resolvedBranchName, access.defaultBranch());
    }

    private String resolveBranchName(BranchStrategy strategy, String supplied, WorkflowSessionId sid) {
        if (strategy == BranchStrategy.DIRECT_MERGE) {
            // Always use a transient working branch even on direct-merge so we
            // can roll back the commit if the merge call fails.
            return config.branchNamePrefix() + sid.value();
        }
        if (supplied == null || supplied.isBlank()) {
            return config.branchNamePrefix() + sid.value();
        }
        return supplied.trim();
    }

    private static String nonBlankOr(String supplied, String fallback) {
        return (supplied == null || supplied.isBlank()) ? fallback : supplied;
    }
}
