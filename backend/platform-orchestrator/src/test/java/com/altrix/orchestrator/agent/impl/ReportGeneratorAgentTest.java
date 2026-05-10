package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.*;
import com.altrix.common.exception.AgentFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportGeneratorAgentTest {

    private final ReportGeneratorAgent agent = new ReportGeneratorAgent();

    @Test
    void exposesNameAndOrder5() {
        assertThat(agent.getName()).isEqualTo("Report Generator");
        assertThat(agent.getOrder()).isEqualTo(5);
    }

    @Test
    void execute_producesMarkdownContainingAllSections() {
        WorkflowOutcome outcome = new WorkflowOutcome(
                "p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", "", "Spring Boot 3 + Kafka", List.of("Step 1: replace PubSub"),
                        "MEDIUM", "3 days", "the plan", List.of()),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));

        MigrationReport report = agent.execute(outcome);

        assertThat(report.projectId()).isEqualTo("p1");
        assertThat(report.content())
                .contains("# Migration Report")
                .contains("p1")
                .contains("the plan")
                .contains("PASSED")
                .contains("Spring Boot 3 + Kafka")
                .contains("MEDIUM")
                .contains("Step 1: replace PubSub");
        assertThat(report.generatedAt()).isNotNull();
    }

    @Test
    void execute_failedValidation_containsIssues() {
        ValidationReport failed = new ValidationReport("p1", false,
                List.of("Listener.java: Pub/Sub import not removed"), "1 issue found");
        WorkflowOutcome outcome = new WorkflowOutcome("p1", null, null, null, failed);

        MigrationReport report = agent.execute(outcome);

        assertThat(report.content()).contains("FAILED");
        assertThat(report.content()).contains("Pub/Sub import not removed");
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
