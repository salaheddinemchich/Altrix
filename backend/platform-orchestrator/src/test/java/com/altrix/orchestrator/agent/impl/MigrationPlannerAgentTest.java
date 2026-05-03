package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.exception.AgentFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlannerAgentTest {

    private final MigrationPlannerAgent agent = new MigrationPlannerAgent();

    @Test
    void exposesNameAndOrder2() {
        assertThat(agent.getName()).isEqualTo("Migration Planner");
        assertThat(agent.getOrder()).isEqualTo(2);
    }

    @Test
    void stub_returnsEmptyPlanCarryingProjectId() {
        AnalysisReport input = new AnalysisReport(
                "p1", List.of("c1"), List.of("i1"), "ran analysis");
        MigrationPlan plan = agent.execute(input);

        assertThat(plan.projectId()).isEqualTo("p1");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.summary()).contains("ran analysis");
    }

    @Test
    void stub_handlesBlankAnalysisSummary() {
        AnalysisReport input = AnalysisReport.empty("p1");
        MigrationPlan plan = agent.execute(input);
        assertThat(plan.summary()).contains("no steps");
    }

    @Test
    void execute_nullInput_throws() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
