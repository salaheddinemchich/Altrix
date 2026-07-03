package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.workflow.MigrationState;
import com.altrix.orchestrator.domain.port.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock
    WorkflowExecutionPort workflowExecution;
    @Mock
    JobStatusUpdatePort jobStatusUpdatePort;
    @Mock
    MigratedFileStoragePort migratedFileStoragePort;
    @Mock
    ProgressNotifierPort progressNotifierPort;
    @Mock
    MigrationPlanCachePort migrationPlanCachePort;
    @Mock
    WorkflowSessionRepository sessionRepository;

    private OrchestratorService service() {
        lenient().when(sessionRepository.save(any(WorkflowSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sessionRepository.findByJobId(any())).thenReturn(java.util.Optional.empty());
        return new OrchestratorService(
                workflowExecution, jobStatusUpdatePort, migratedFileStoragePort,
                progressNotifierPort, migrationPlanCachePort,
                sessionRepository, 3, null);
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void run_invokesWorkflow_andMarksDone() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java")
                .content("content").changeType(FileChangeType.MODIFIED)
                .diffSummary("migrated").build();

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        MigrationArtifact artifact = new MigrationArtifact("proj-1", List.of(file), "done", null);
        MigrationState result = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT, initial,
                MigrationState.MIGRATION_ARTIFACT, artifact,
                MigrationState.RETRY_COUNT, 0));

        when(workflowExecution.execute(initial)).thenReturn(result);
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        ProjectContext outcome = service().run(initial);

        assertThat(outcome.migratedFiles()).hasSize(1);
        verify(jobStatusUpdatePort).markAnalyzing("job-1");
        verify(jobStatusUpdatePort).markMigrating("job-1");
        verify(jobStatusUpdatePort).markDone("job-1", "migrated/job-1/output.zip");
        verify(progressNotifierPort).notify(eq("job-1"), eq("Pipeline"), eq("DONE"), any());
    }

    // ── Regression: job d2a0e15e-b8e5-4a1c-8694-2d8bf1cc0895 reached END with
    // a FAILED sandbox validation (compile/test/boot all failed, report said
    // DO_NOT_DEPLOY) but was still marked DONE, because the graph completing
    // without throwing was wrongly treated as success. ──────────────────────

    @Test
    void run_marksFailed_whenWorkflowCompletesButSandboxValidationFailed() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java")
                .content("content").changeType(FileChangeType.MODIFIED)
                .diffSummary("migrated").build();

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        MigrationArtifact artifact = new MigrationArtifact("proj-1", List.of(file), "done", null);
        ValidationReport failedValidation = new ValidationReport(
                "proj-1", false, List.of("compile failed"), "Compile, tests, and boot health all failed");
        MigrationPlan plan = new MigrationPlan(
                "proj-1", "storage-key", "Spring Boot 3 + Kafka",
                List.of(), "MEDIUM", "1d", "summary", List.of("A.java"), null);
        MigrationState result = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT, initial,
                MigrationState.MIGRATION_PLAN, plan,
                MigrationState.MIGRATION_ARTIFACT, artifact,
                MigrationState.VALIDATION_REPORT, failedValidation,
                MigrationState.RETRY_COUNT, 0));

        when(workflowExecution.execute(initial)).thenReturn(result);
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        service().run(initial);

        verify(jobStatusUpdatePort, never()).markDone(any(), any());
        verify(jobStatusUpdatePort).markFailed(eq("job-1"), contains("Compile, tests, and boot health all failed"));
        verify(progressNotifierPort).notify(eq("job-1"), eq("Pipeline"), eq("FAILED"), any());

        // Regression: the session row's errorMessage must carry the SAME
        // "Sandbox validation failed:" prefix as the job's — the frontend's
        // stage-backfill heuristic reads session.errorMessage first and only
        // recognizes that exact prefix to highlight the Validate stage.
        // Before this fix, the session got the bare summary (no prefix),
        // so the timeline always defaulted to highlighting "Analyse".
        ArgumentCaptor<WorkflowSession> sessionCaptor = ArgumentCaptor.forClass(WorkflowSession.class);
        verify(sessionRepository, atLeastOnce()).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().errorMessage())
                .startsWith("Sandbox validation failed:")
                .contains("Compile, tests, and boot health all failed");
    }

    @Test
    void run_marksDone_whenValidationReportPresentAndPassed() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java")
                .content("content").changeType(FileChangeType.MODIFIED)
                .diffSummary("migrated").build();

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        MigrationArtifact artifact = new MigrationArtifact("proj-1", List.of(file), "done", null);
        ValidationReport passedValidation = new ValidationReport(
                "proj-1", true, List.of(), "All checks passed");
        MigrationPlan plan = new MigrationPlan(
                "proj-1", "storage-key", "Spring Boot 3 + Kafka",
                List.of(), "MEDIUM", "1d", "summary", List.of("A.java"), null);
        MigrationState result = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT, initial,
                MigrationState.MIGRATION_PLAN, plan,
                MigrationState.MIGRATION_ARTIFACT, artifact,
                MigrationState.VALIDATION_REPORT, passedValidation,
                MigrationState.RETRY_COUNT, 0));

        when(workflowExecution.execute(initial)).thenReturn(result);
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        service().run(initial);

        verify(jobStatusUpdatePort).markDone("job-1", "migrated/job-1/output.zip");
        verify(jobStatusUpdatePort, never()).markFailed(any(), any());
    }

    @Test
    void run_cachesResultAfterSuccess() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java").content("x")
                .changeType(FileChangeType.MODIFIED).diffSummary("ok").build();
        ProjectContext initial = ProjectContext.builder()
                .jobId("j").projectId("p").build();

        MigrationArtifact artifact = new MigrationArtifact("p", List.of(file), "ok", null);
        MigrationState state = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT, initial,
                MigrationState.MIGRATION_ARTIFACT, artifact,
                MigrationState.RETRY_COUNT, 0));

        when(workflowExecution.execute(initial)).thenReturn(state);
        when(migratedFileStoragePort.storeMigratedZip(any(), any())).thenReturn("out.zip");

        service().run(initial);

        verify(migrationPlanCachePort).store(eq("p"), any(), eq(List.of(file)));
    }

    // ── failure handling ──────────────────────────────────────────────────────

    @Test
    void run_marksFailedAndRethrows_whenWorkflowThrows() {
        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();
        when(workflowExecution.execute(any())).thenThrow(new RuntimeException("AI down"));

        assertThatThrownBy(() -> service().run(initial))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusUpdatePort).markFailed(eq("job-1"), any());
        verify(progressNotifierPort).notify(eq("job-1"), eq("Pipeline"), eq("FAILED"), any());
    }

    // ── graceful degradation (#148) ───────────────────────────────────────────

    @Test
    void run_servesCache_whenAllProvidersUnavailableAndCacheHit() {
        when(workflowExecution.execute(any()))
                .thenThrow(new AiProviderUnavailableException("all providers down"));

        MigratedFile cached = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java").content("cached")
                .changeType(FileChangeType.MODIFIED).diffSummary("cached").build();
        when(migrationPlanCachePort.loadLatest(eq("proj-1"), any()))
                .thenReturn(Optional.of(List.of(cached)));
        when(migratedFileStoragePort.storeMigratedZip(any(), any()))
                .thenReturn("out.zip");

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        ProjectContext result = service().run(initial);

        assertThat(result.migratedFiles()).containsExactly(cached);
        verify(jobStatusUpdatePort).markDone(eq("job-1"), any());
        verify(progressNotifierPort).notify(eq("job-1"), eq("Pipeline"), eq("DONE"), any());
    }

    @Test
    void run_throwsAiUnavailable_whenProvidersDownAndNoCacheEntry() {
        when(workflowExecution.execute(any()))
                .thenThrow(new AiProviderUnavailableException("no providers"));
        when(migrationPlanCachePort.loadLatest(any(), any())).thenReturn(Optional.empty());

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        assertThatThrownBy(() -> service().run(initial))
                .isInstanceOf(AiProviderUnavailableException.class);

        verify(jobStatusUpdatePort).markFailed(eq("job-1"), any());
    }

    // ── empty-artifact path ───────────────────────────────────────────────────

    @Test
    void run_withEmptyArtifact_storesEmptyZipAndMarksDone() {
        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();
        MigrationState emptyState = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT, initial,
                MigrationState.RETRY_COUNT, 0));

        when(workflowExecution.execute(initial)).thenReturn(emptyState);
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        service().run(initial);

        verify(jobStatusUpdatePort).markDone("job-1", "migrated/job-1/output.zip");
        verify(jobStatusUpdatePort, never()).markMigrating(any());
    }
}
