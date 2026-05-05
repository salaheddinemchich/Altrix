package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.model.workflow.MigrationState;
import com.altrix.orchestrator.domain.port.out.AgentPort;
import com.altrix.orchestrator.domain.port.out.CodeIndexingPort;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationPlanCachePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowExecutionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock WorkflowExecutionPort  workflowExecution;
    @Mock JobStatusUpdatePort    jobStatusUpdatePort;
    @Mock MigratedFileStoragePort migratedFileStoragePort;
    @Mock ProgressNotifierPort   progressNotifierPort;
    @Mock CodeIndexingPort       codeIndexingPort;
    @Mock MigrationPlanCachePort migrationPlanCachePort;

    private OrchestratorService service() {
        return new OrchestratorService(
                workflowExecution, jobStatusUpdatePort, migratedFileStoragePort,
                progressNotifierPort, codeIndexingPort, migrationPlanCachePort);
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

        MigrationArtifact artifact = new MigrationArtifact("proj-1", List.of(file), "done");
        MigrationState result = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT,    initial,
                MigrationState.MIGRATION_ARTIFACT, artifact,
                MigrationState.RETRY_COUNT,        0));

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

    @Test
    void run_cachesResultAfterSuccess() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java").content("x")
                .changeType(FileChangeType.MODIFIED).diffSummary("ok").build();
        ProjectContext initial = ProjectContext.builder()
                .jobId("j").projectId("p").build();

        MigrationArtifact artifact = new MigrationArtifact("p", List.of(file), "ok");
        MigrationState state = new MigrationState(Map.of(
                MigrationState.PROJECT_CONTEXT,    initial,
                MigrationState.MIGRATION_ARTIFACT, artifact,
                MigrationState.RETRY_COUNT,        0));

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
                MigrationState.RETRY_COUNT,     0));

        when(workflowExecution.execute(initial)).thenReturn(emptyState);
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        service().run(initial);

        verify(jobStatusUpdatePort).markDone("job-1", "migrated/job-1/output.zip");
        verify(jobStatusUpdatePort, never()).markMigrating(any());
    }

    // ── AgentPort type invariants ─────────────────────────────────────────────

    @Test
    void agentPort_is_a_subtype_of_MigrationAgent() {
        assertThat(MigrationAgent.class).isAssignableFrom(AgentPort.class);
    }

    @Test
    void agentPort_mock_is_usable_as_MigrationAgent() {
        AgentPort agent = mock(AgentPort.class);
        when(agent.getName()).thenReturn("test-agent");
        when(agent.getOrder()).thenReturn(1);

        MigrationAgent<ProjectContext, ProjectContext> typed = agent;
        assertThat(typed.getName()).isEqualTo("test-agent");
        assertThat(typed.getOrder()).isEqualTo(1);
    }
}
