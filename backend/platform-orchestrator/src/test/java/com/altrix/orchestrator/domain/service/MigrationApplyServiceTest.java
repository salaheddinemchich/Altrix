package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyDecision;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyOutcome;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.apply.RepositoryPermission;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase;
import com.altrix.orchestrator.domain.port.in.ConfirmAndApplyMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort.State;
import com.altrix.orchestrator.domain.port.out.OAuthTokenLookupPort;
import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort;
import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort.ProjectMetadata;
import com.altrix.orchestrator.infrastructure.apply.BranchStrategyHandler;
import com.altrix.orchestrator.infrastructure.apply.BranchStrategyHandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationApplyServiceTest {

    private final MigrationApplyStatePort applyState = mock(MigrationApplyStatePort.class);
    private final MigrationApplyAuditLogPort auditLog = mock(MigrationApplyAuditLogPort.class);
    private final ProjectMetadataLookupPort projects = mock(ProjectMetadataLookupPort.class);
    private final OAuthTokenLookupPort tokens = mock(OAuthTokenLookupPort.class);
    private final CheckRepositoryAccessUseCase accessUseCase = mock(CheckRepositoryAccessUseCase.class);
    private final BranchStrategyHandler handler = mock(BranchStrategyHandler.class);
    private final BranchStrategyHandlerRegistry registry = mock(BranchStrategyHandlerRegistry.class);

    private final MigrationApplyService service = new MigrationApplyService(
            applyState, auditLog, projects, tokens, accessUseCase, registry);

    private final WorkflowSessionId sid = WorkflowSessionId.generate();
    private final UUID validToken = UUID.randomUUID();

    @BeforeEach
    void wireStrategySetSession() {
        when(applyState.find(sid)).thenReturn(Optional.of(new State(
                sid, "proj-1", "DONE",
                BranchStrategy.PULL_REQUEST, "feature/x", "main",
                "commit", "PR title", "PR body",
                validToken, Instant.now().plusSeconds(900),
                "STRATEGY_SET", null, null, null,
                "user-1", null,
                List.of(MigratedFile.builder()
                        .originalPath("a.java").newPath("a.java")
                        .content("hello")
                        .changeType(FileChangeType.MODIFIED).build()))));
        when(projects.findById("proj-1")).thenReturn(Optional.of(
                new ProjectMetadata("proj-1", "owner/repo", "main", "github")));
        when(tokens.findAccessToken("user-1", "github")).thenReturn(Optional.of("tok"));
        when(accessUseCase.check("proj-1", "user-1")).thenReturn(new RepositoryAccess(
                "owner/repo", "main", RepositoryPermission.WRITE, true, false,
                Set.of(BranchStrategy.NEW_BRANCH, BranchStrategy.PULL_REQUEST)));
        when(registry.handlerFor(BranchStrategy.PULL_REQUEST)).thenReturn(handler);
    }

    @Test
    void apply_dispatchesToHandler_andPersistsOutcome() {
        when(handler.apply(any(MigrationApplyDecision.class), eq("tok"), eq("owner/repo"), anyList()))
                .thenReturn(new MigrationApplyResult(
                        MigrationApplyOutcome.PR_CREATED, "feature/x", "sha1",
                        "https://github.com/owner/repo/pull/1", 1, null,
                        "ok", Instant.now()));

        var result = service.apply(new ConfirmAndApplyMigrationUseCase.Request(
                sid, BranchStrategy.PULL_REQUEST, "feature/x",
                "commit", "PR title", "PR body",
                validToken, true, "user-1"));

        assertThat(result.outcome()).isEqualTo(MigrationApplyOutcome.PR_CREATED);
        verify(applyState).saveOutcome(eq(sid), eq(MigrationApplyOutcome.PR_CREATED),
                eq("https://github.com/owner/repo/pull/1"), eq("sha1"), any(Instant.class));
        verify(auditLog, atLeastOnce()).save(argThat(e -> e.eventType().name().equals("APPLY_CONFIRMED")));
        verify(auditLog, atLeastOnce()).save(argThat(e -> e.eventType().name().equals("PR_CREATED")));
    }

    @Test
    void apply_rejectsMismatchedConfirmationToken() {
        assertThatThrownBy(() -> service.apply(new ConfirmAndApplyMigrationUseCase.Request(
                sid, BranchStrategy.PULL_REQUEST, "feature/x",
                "commit", "PR title", "PR body",
                UUID.randomUUID(),                              // wrong token
                true, "user-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        verify(handler, never()).apply(any(), anyString(), anyString(), anyList());
    }

    @Test
    void apply_rejectsMissingApproval() {
        assertThatThrownBy(() -> service.apply(new ConfirmAndApplyMigrationUseCase.Request(
                sid, BranchStrategy.PULL_REQUEST, "feature/x",
                "commit", "PR title", "PR body",
                validToken, false, "user-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval flag");
    }

    @Test
    void apply_rejectsStrategyMismatch() {
        assertThatThrownBy(() -> service.apply(new ConfirmAndApplyMigrationUseCase.Request(
                sid, BranchStrategy.DIRECT_MERGE,                // different from stored
                "feature/x", "commit", "PR title", "PR body",
                validToken, true, "user-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Strategy mismatch");
    }

    @Test
    void cancel_marksCancelled_andAudits_andDoesNotCallHandler() {
        var result = service.cancel(sid, "user-1");
        assertThat(result.outcome()).isEqualTo(MigrationApplyOutcome.CANCELLED);
        verify(applyState).markCancelled(eq(sid), any(Instant.class));
        verify(auditLog).save(argThat(e -> e.eventType().name().equals("CANCELLED")));
        verifyNoInteractions(handler);
    }

    @Test
    void apply_returnsFailed_whenStrategyNoLongerPermitted() {
        // Revoked access between choose and apply.
        when(accessUseCase.check("proj-1", "user-1")).thenReturn(new RepositoryAccess(
                "owner/repo", "main", RepositoryPermission.READ, false, false, Set.of()));

        var result = service.apply(new ConfirmAndApplyMigrationUseCase.Request(
                sid, BranchStrategy.PULL_REQUEST, "feature/x",
                "commit", "PR title", "PR body",
                validToken, true, "user-1"));

        assertThat(result.outcome()).isEqualTo(MigrationApplyOutcome.FAILED);
        verify(handler, never()).apply(any(), anyString(), anyString(), anyList());
    }
}
