package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.ContextAnalysisCachePort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextAnalyzerAgentTest {

    @Mock AiPort aiPort;
    @Mock FileReaderPort fileReader;
    @Mock ContextAnalysisCachePort analysisCache;
    @InjectMocks ContextAnalyzerAgent agent;

    private static final Map<String, String> SINGLE_FILE =
            Map.of("Foo.java", "import google.cloud.pubsub; class Foo {}");
    private static final String AI_RESPONSE =
            "{\"detectedComponents\":[\"com.x.PubSubPublisher\"],\"detectedIntegrations\":[\"Google Pub/Sub\"],\"summary\":\"PubSub project\"}";

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
        verifyNoInteractions(aiPort, fileReader, analysisCache);
    }

    @Test
    void execute_cacheMiss_callsAiAndStoresResult() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .listenerClasses(List.of("com.x.MyListener"))
                .build();

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(SINGLE_FILE);
        when(analysisCache.get(anyString())).thenReturn(Optional.empty());
        when(aiPort.chatFast(anyString(), anyString())).thenReturn(AI_RESPONSE);

        AnalysisReport report = agent.execute(ctx);

        assertThat(report.storageKey()).isEqualTo("uploads/p1.zip");
        assertThat(report.detectedComponents()).contains("com.x.PubSubPublisher", "com.x.MyListener");
        assertThat(report.detectedIntegrations()).contains("Google Pub/Sub");
        assertThat(report.summary()).isEqualTo("PubSub project");
        verify(aiPort).chatFast(anyString(), anyString());
        verify(analysisCache).put(anyString(), any(AnalysisReport.class));
    }

    @Test
    void execute_cacheHit_returnsDirectly_noAiCall() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .build();

        AnalysisReport cached = new AnalysisReport("p1", "uploads/p1.zip",
                List.of("com.x.Cached"), List.of("Kafka"), "cached summary");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(SINGLE_FILE);
        when(analysisCache.get(anyString())).thenReturn(Optional.of(cached));

        AnalysisReport report = agent.execute(ctx);

        assertThat(report).isSameAs(cached);
        verifyNoInteractions(aiPort);
        verify(analysisCache, never()).put(anyString(), any());
    }

    @Test
    void execute_forceFresh_evictsCacheAndCallsAi() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .forceFresh(true)
                .build();

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(SINGLE_FILE);
        when(aiPort.chatFast(anyString(), anyString())).thenReturn(AI_RESPONSE);

        AnalysisReport report = agent.execute(ctx);

        verify(analysisCache).evict(anyString());
        verify(analysisCache, never()).get(anyString());
        verify(aiPort).chatFast(anyString(), anyString());
        verify(analysisCache).put(anyString(), any(AnalysisReport.class));
        assertThat(report.summary()).isEqualTo("PubSub project");
    }

    @Test
    void execute_forceFreshNull_treatedAsFalse_doesNotEvict() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .forceFresh(null)
                .build();

        AnalysisReport cached = new AnalysisReport("p1", "uploads/p1.zip",
                List.of("A"), List.of("B"), "ok");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(SINGLE_FILE);
        when(analysisCache.get(anyString())).thenReturn(Optional.of(cached));

        agent.execute(ctx);

        verify(analysisCache, never()).evict(anyString());
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

    @Test
    void computeContentHash_stableAcrossCalls() {
        Map<String, String> files = Map.of("A.java", "class A{}", "B.java", "class B{}");

        String hash1 = ContextAnalyzerAgent.computeContentHash(files);
        String hash2 = ContextAnalyzerAgent.computeContentHash(files);

        assertThat(hash1).isEqualTo(hash2).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void computeContentHash_differentContents_differentHashes() {
        Map<String, String> files1 = Map.of("A.java", "class A{}");
        Map<String, String> files2 = Map.of("A.java", "class A{ int x; }");

        assertThat(ContextAnalyzerAgent.computeContentHash(files1))
                .isNotEqualTo(ContextAnalyzerAgent.computeContentHash(files2));
    }
}
