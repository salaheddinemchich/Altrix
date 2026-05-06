package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextAnalyzerAgentTest {

    @Mock AiPort        aiPort;
    @Mock FileReaderPort fileReader;
    @InjectMocks ContextAnalyzerAgent agent;

    @Test
    void implementsTypedMigrationAgentInterface() {
        assertThat(agent).isInstanceOf(MigrationAgent.class);
    }

    @Test
    void exposesNameAndOrder1() {
        assertThat(agent.getName()).isEqualTo("Context Analyzer");
        assertThat(agent.getOrder()).isEqualTo(1);
    }

    @Test
    void execute_noStorageKey_aggregatesFromContextFields_noAiCalled() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1")
                .listenerClasses(List.of("com.x.MyListener"))
                .publisherClasses(List.of("com.x.MyPublisher"))
                .pubSubTopics(List.of("topic-a"))
                .pubSubSubscriptions(List.of("sub-a"))
                .build();

        AnalysisReport report = agent.execute(ctx);

        assertThat(report.projectId()).isEqualTo("p1");
        assertThat(report.storageKey()).isEmpty();
        assertThat(report.detectedComponents()).containsExactly("com.x.MyListener", "com.x.MyPublisher");
        assertThat(report.detectedIntegrations()).containsExactly("topic-a", "sub-a");
        assertThat(report.summary()).contains("p1").contains("2 component(s)").contains("2 integration(s)");
        verifyNoInteractions(aiPort, fileReader);
    }

    @Test
    void execute_withStorageKey_aiEnriches_andMergesWithContextFields() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .listenerClasses(List.of("com.x.MyListener"))
                .build();

        when(fileReader.readSourceFiles("uploads/p1.zip"))
                .thenReturn(Map.of("Foo.java", "import google.cloud.pubsub; class Foo {}"));
        when(aiPort.chatFast(anyString(), anyString())).thenReturn(
                "{\"detectedComponents\":[\"com.x.PubSubPublisher\"],\"detectedIntegrations\":[\"Google Pub/Sub\"],\"summary\":\"PubSub project\"}");

        AnalysisReport report = agent.execute(ctx);

        assertThat(report.storageKey()).isEqualTo("uploads/p1.zip");
        assertThat(report.detectedComponents()).contains("com.x.PubSubPublisher", "com.x.MyListener");
        assertThat(report.detectedIntegrations()).contains("Google Pub/Sub");
        assertThat(report.summary()).isEqualTo("PubSub project");
    }

    @Test
    void execute_aiFails_fallsBackToContextFields() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .listenerClasses(List.of("com.x.MyListener"))
                .build();

        when(fileReader.readSourceFiles(anyString())).thenThrow(new RuntimeException("MinIO unreachable"));

        AnalysisReport report = agent.execute(ctx);

        assertThat(report.detectedComponents()).containsExactly("com.x.MyListener");
        assertThat(report.storageKey()).isEqualTo("uploads/p1.zip");
        assertThat(report.summary()).contains("AI unavailable");
    }

    @Test
    void execute_emptyContext_handlesGracefully() {
        ProjectContext ctx = ProjectContext.builder().jobId("j1").projectId("p1").build();

        AnalysisReport report = agent.execute(ctx);

        assertThat(report.detectedComponents()).isEmpty();
        assertThat(report.detectedIntegrations()).isEmpty();
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
