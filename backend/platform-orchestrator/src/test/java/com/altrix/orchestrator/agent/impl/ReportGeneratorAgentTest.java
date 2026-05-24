package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.*;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class ReportGeneratorAgentTest {

    // Default suite uses a stub AiPort that returns null — exercises the
    // template-only path so existing assertions keep verifying the
    // deterministic markdown.  Narrative-specific behaviour has its own
    // tests below.
    private final AiPort aiPort = stubAi(null);
    private final ReportGeneratorAgent agent =
            new ReportGeneratorAgent(aiPort, /*narrativeEnabled*/ false);

    private static AiPort stubAi(String narrative) {
        AiPort port = Mockito.mock(AiPort.class);
        Mockito.when(port.chatFast(any(), any())).thenReturn(narrative);
        Mockito.when(port.chat(any(), any())).thenReturn(narrative);
        return port;
    }

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

    // ── #160 — AI-narrated executive summary ──────────────────────────────────

    @Test
    void narrativeEnabled_withAiReturningText_includesExecutiveSummarySection() {
        AiPort ai = stubAi("Migration completed cleanly with low risk; 3 files were rewritten and all validation checks passed.");
        ReportGeneratorAgent narrAgent = new ReportGeneratorAgent(ai, true);

        WorkflowOutcome outcome = new WorkflowOutcome("p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", "", "Kafka", List.of(), "LOW", "1d", "summary", List.of()),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));

        String markdown = narrAgent.execute(outcome).content();
        assertThat(markdown).contains("## Executive summary");
        assertThat(markdown).contains("Migration completed cleanly");
    }

    @Test
    void narrativeEnabled_butAiReturnsNull_skipsSectionGracefully() {
        // stubAi(null) already configured; just enable narrative on a fresh agent.
        ReportGeneratorAgent narrAgent = new ReportGeneratorAgent(stubAi(null), true);
        WorkflowOutcome outcome = new WorkflowOutcome("p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", "", "Kafka", List.of(), "LOW", "1d", "summary", List.of()),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));

        String markdown = narrAgent.execute(outcome).content();
        // Template still ships; the section is just absent.
        assertThat(markdown).doesNotContain("## Executive summary");
        assertThat(markdown).contains("# Migration Report");
    }

    @Test
    void narrativeEnabled_butAiThrows_skipsSectionGracefully() {
        AiPort throwing = Mockito.mock(AiPort.class);
        Mockito.when(throwing.chatFast(any(), any()))
                .thenThrow(new RuntimeException("provider unavailable"));
        ReportGeneratorAgent narrAgent = new ReportGeneratorAgent(throwing, true);
        WorkflowOutcome outcome = new WorkflowOutcome("p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", "", "Kafka", List.of(), "LOW", "1d", "summary", List.of()),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));

        // Must NOT propagate — the report still ships.
        String markdown = narrAgent.execute(outcome).content();
        assertThat(markdown).doesNotContain("## Executive summary");
        assertThat(markdown).contains("# Migration Report");
    }

    @Test
    void narrativeDisabled_doesNotCallAi() {
        AiPort ai = Mockito.mock(AiPort.class);
        ReportGeneratorAgent narrAgent = new ReportGeneratorAgent(ai, /*narrativeEnabled*/ false);
        WorkflowOutcome outcome = new WorkflowOutcome("p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", "", "Kafka", List.of(), "LOW", "1d", "summary", List.of()),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));

        narrAgent.execute(outcome);

        Mockito.verifyNoInteractions(ai);
    }
}
