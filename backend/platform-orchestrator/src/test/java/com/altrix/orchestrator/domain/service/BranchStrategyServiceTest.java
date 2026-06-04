package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.RepositoryAccess;
import com.altrix.orchestrator.domain.model.apply.RepositoryPermission;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase;
import com.altrix.orchestrator.domain.port.in.ChooseBranchStrategyUseCase.Outcome;
import com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort;
import com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort.State;
import com.altrix.orchestrator.infrastructure.config.MigrationApplyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BranchStrategyServiceTest {

    private final CheckRepositoryAccessUseCase access = mock(CheckRepositoryAccessUseCase.class);
    private final MigrationApplyStatePort applyState = mock(MigrationApplyStatePort.class);
    private final MigrationApplyAuditLogPort auditLog = mock(MigrationApplyAuditLogPort.class);
    private final MigrationApplyConfig config = new MigrationApplyConfig(
            "altrix/migration-",
            "msg {sessionId}",
            "PR title {sessionId}",
            "PR body {sessionId}",
            "^[A-Za-z0-9._/-]{1,128}$",
            Duration.ofMinutes(15));

    private final BranchStrategyService service = new BranchStrategyService(access, applyState, auditLog, config);

    private final WorkflowSessionId sid = WorkflowSessionId.generate();

    @BeforeEach
    void wireUpDoneSession() {
        when(applyState.find(sid)).thenReturn(Optional.of(new State(
                sid, "proj-1", "DONE",
                null, null, null, null, null, null, null, null, null, null, null, null,
                "user-1", null,
                List.of(MigratedFile.builder().originalPath("a.java").newPath("a.java")
                        .content("x").changeType(FileChangeType.MODIFIED).build()))));
    }

    @Test
    void choose_persistsStrategyAndIssuesToken_forAdmin() {
        when(access.check("proj-1", "user-1")).thenReturn(new RepositoryAccess(
                "owner/repo", "main", RepositoryPermission.ADMIN, true, true,
                Set.of(BranchStrategy.NEW_BRANCH, BranchStrategy.PULL_REQUEST, BranchStrategy.DIRECT_MERGE)));

        Outcome out = service.choose(sid, BranchStrategy.PULL_REQUEST, "feature/x",
                null, null, null, "user-1");

        assertThat(out.confirmationToken()).isNotNull();
        assertThat(out.strategy()).isEqualTo(BranchStrategy.PULL_REQUEST);
        assertThat(out.targetBranchName()).isEqualTo("feature/x");
        assertThat(out.baseBranch()).isEqualTo("main");
        verify(applyState).saveStrategyChoice(eq(sid), eq(BranchStrategy.PULL_REQUEST),
                eq("feature/x"), eq("main"), anyString(), anyString(), anyString(),
                any(UUID.class), any(), eq("user-1"));
        verify(auditLog).save(argThat(e ->
                e.eventType().name().equals("STRATEGY_CHOSEN")));
    }

    @Test
    void choose_rejectsStrategyTheUserCannotPerform() {
        // Member without push rights — only READ.
        when(access.check("proj-1", "user-1")).thenReturn(new RepositoryAccess(
                "owner/repo", "main", RepositoryPermission.READ, false, false, Set.of()));

        assertThatThrownBy(() -> service.choose(sid, BranchStrategy.DIRECT_MERGE, "x",
                null, null, null, "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not permitted");
    }

    @Test
    void choose_rejectsBranchNameMatchingDefault() {
        when(access.check("proj-1", "user-1")).thenReturn(new RepositoryAccess(
                "owner/repo", "main", RepositoryPermission.WRITE, true, false,
                Set.of(BranchStrategy.NEW_BRANCH, BranchStrategy.PULL_REQUEST)));

        assertThatThrownBy(() -> service.choose(sid, BranchStrategy.NEW_BRANCH, "main",
                null, null, null, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from default branch");
    }

    @Test
    void choose_rejectsBranchNameThatFailsPattern() {
        when(access.check("proj-1", "user-1")).thenReturn(new RepositoryAccess(
                "owner/repo", "main", RepositoryPermission.WRITE, true, false,
                Set.of(BranchStrategy.NEW_BRANCH, BranchStrategy.PULL_REQUEST)));

        assertThatThrownBy(() -> service.choose(sid, BranchStrategy.NEW_BRANCH, "bad branch with spaces",
                null, null, null, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required pattern");
    }

    @Test
    void choose_failsWhenSessionNotInDone() {
        WorkflowSessionId other = WorkflowSessionId.generate();
        when(applyState.find(other)).thenReturn(Optional.of(new State(
                other, "p", "MIGRATING", null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, List.of(MigratedFile.builder().originalPath("a").newPath("a").content("x").changeType(FileChangeType.MODIFIED).build()))));
        assertThatThrownBy(() -> service.choose(other, BranchStrategy.NEW_BRANCH, "x", null, null, null, "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration must finish");
    }
}
