package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
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
    void stub_producesReportContainingPlanSummaryAndValidationVerdict() {
        WorkflowOutcome outcome = new WorkflowOutcome(
                "p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", List.of(), "the plan"),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));

        MigrationReport report = agent.execute(outcome);

        assertThat(report.projectId()).isEqualTo("p1");
        assertThat(report.content())
                .contains("p1")
                .contains("the plan")
                .contains("passed");
    }

    @Test
    void execute_nullInput_throws() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
