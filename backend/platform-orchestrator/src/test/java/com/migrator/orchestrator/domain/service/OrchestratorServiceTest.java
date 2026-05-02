package com.migrator.orchestrator.domain.service;

import com.migrator.common.domain.model.MigratedFile;
import com.migrator.common.domain.model.ProjectContext;
import com.migrator.common.domain.enums.FileChangeType;
import com.migrator.common.domain.port.MigrationAgent;
import com.migrator.orchestrator.domain.port.out.AgentPort;
import com.migrator.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.migrator.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.migrator.orchestrator.domain.port.out.ProgressNotifierPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock JobStatusUpdatePort     jobStatusUpdatePort;
    @Mock MigratedFileStoragePort migratedFileStoragePort;
    @Mock ProgressNotifierPort    progressNotifierPort;

    private OrchestratorService service(List<AgentPort> agents) {
        return new OrchestratorService(
                agents, jobStatusUpdatePort, migratedFileStoragePort,
                progressNotifierPort, 0L);
    }

    private OrchestratorService service(List<AgentPort> agents, long delayMs) {
        return new OrchestratorService(
                agents, jobStatusUpdatePort, migratedFileStoragePort,
                progressNotifierPort, delayMs);
    }

    @Test
    void run_executesAgentsInOrder_andMarksDone() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("A.java").newPath("A.java")
                .content("content").changeType(FileChangeType.MODIFIED)
                .diffSummary("migrated").build();

        AgentPort agent1 = mock(AgentPort.class);
        AgentPort agent3 = mock(AgentPort.class);
        when(agent1.getOrder()).thenReturn(1);
        when(agent3.getOrder()).thenReturn(3);
        when(agent1.getName()).thenReturn("Analyzer");
        when(agent3.getName()).thenReturn("Migrator");

        ProjectContext after1 = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build()
                .withPubSubTopics(List.of("orders.created"));
        ProjectContext after3 = after1.withMigratedFiles(List.of(file));

        when(agent1.execute(any())).thenReturn(after1);
        when(agent3.execute(any())).thenReturn(after3);
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        ProjectContext result = service(List.of(agent3, agent1)).run(initial); // deliberate wrong order

        assertThat(result.migratedFiles()).hasSize(1);
        verify(jobStatusUpdatePort).markAnalyzing("job-1");
        verify(jobStatusUpdatePort).markMigrating("job-1");
        verify(jobStatusUpdatePort).markDone("job-1", "migrated/job-1/output.zip");
        verify(progressNotifierPort).notify(eq("job-1"), eq("Pipeline"), eq("DONE"), any());
    }

    @Test
    void run_marksFailedAndRethrows_whenAgentThrows() {
        AgentPort failingAgent = mock(AgentPort.class);
        when(failingAgent.getOrder()).thenReturn(1);
        when(failingAgent.getName()).thenReturn("BrokenAgent");
        when(failingAgent.execute(any())).thenThrow(new RuntimeException("AI down"));

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        assertThatThrownBy(() -> service(List.of(failingAgent)).run(initial))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusUpdatePort).markFailed(eq("job-1"), any());
        verify(progressNotifierPort).notify(eq("job-1"), eq("Pipeline"), eq("FAILED"), any());
    }

    @Test
    void run_withNoAgents_storeEmptyZipAndMarksDone() {
        when(migratedFileStoragePort.storeMigratedZip(eq("job-1"), any()))
                .thenReturn("migrated/job-1/output.zip");

        ProjectContext initial = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        service(List.of()).run(initial);

        verify(jobStatusUpdatePort).markDone("job-1", "migrated/job-1/output.zip");
    }

    // -----------------------------------------------------------------------
    // Type hierarchy: AgentPort must be a MigrationAgent<ProjectContext, ProjectContext>
    // -----------------------------------------------------------------------

    @Test
    void agentPort_is_a_subtype_of_MigrationAgent() {
        assertThat(MigrationAgent.class).isAssignableFrom(AgentPort.class);
    }

    @Test
    void agentPort_mock_is_usable_as_MigrationAgent() {
        AgentPort agent = mock(AgentPort.class);
        when(agent.getName()).thenReturn("test-agent");
        when(agent.getOrder()).thenReturn(1);

        // Cast must succeed — AgentPort IS-A MigrationAgent<ProjectContext, ProjectContext>
        MigrationAgent<ProjectContext, ProjectContext> typed = agent;
        assertThat(typed.getName()).isEqualTo("test-agent");
        assertThat(typed.getOrder()).isEqualTo(1);
    }

    @Test
    void run_pausesBetweenAgents_whenDelayIsPositive() {
        AgentPort a1 = mock(AgentPort.class);
        AgentPort a2 = mock(AgentPort.class);
        when(a1.getOrder()).thenReturn(2);
        when(a2.getOrder()).thenReturn(4);
        when(a1.getName()).thenReturn("A");
        when(a2.getName()).thenReturn("B");

        ProjectContext ctx = ProjectContext.builder().jobId("job-1").projectId("p").build();
        when(a1.execute(any())).thenReturn(ctx);
        when(a2.execute(any())).thenReturn(ctx);
        when(migratedFileStoragePort.storeMigratedZip(any(), any())).thenReturn("out.zip");

        service(List.of(a1, a2), 1L).run(ctx);

        verify(a1).execute(any());
        verify(a2).execute(any());
        verify(jobStatusUpdatePort).markDone("job-1", "out.zip");
    }
}
