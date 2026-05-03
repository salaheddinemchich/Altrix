package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAnalyzerAgentTest {

    private final ContextAnalyzerAgent agent = new ContextAnalyzerAgent();

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
    void execute_aggregatesComponentsAndIntegrationsFromContext() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").storageKey("uploads/p1.zip")
                .pubSubTopics(List.of("topic-a"))
                .pubSubSubscriptions(List.of("sub-a"))
                .listenerClasses(List.of("com.x.MyListener"))
                .publisherClasses(List.of("com.x.MyPublisher"))
                .build();

        AnalysisReport report = agent.execute(ctx);

        assertThat(report.projectId()).isEqualTo("p1");
        assertThat(report.detectedComponents())
                .containsExactly("com.x.MyListener", "com.x.MyPublisher");
        assertThat(report.detectedIntegrations())
                .containsExactly("topic-a", "sub-a");
        assertThat(report.summary()).contains("p1").contains("2 components").contains("2 integrations");
    }

    @Test
    void execute_handlesEmptyContext() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("j1").projectId("p1").build();

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
