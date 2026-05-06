package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationPlannerAgentTest {

    @Mock AiPort aiPort;
    @InjectMocks MigrationPlannerAgent agent;

    @Test
    void exposesNameAndOrder2() {
        assertThat(agent.getName()).isEqualTo("Migration Planner");
        assertThat(agent.getOrder()).isEqualTo(2);
    }

    @Test
    void execute_aiProvidesStructuredPlan() {
        AnalysisReport input = new AnalysisReport(
                "p1", "uploads/p1.zip", List.of("c1"), List.of("i1"), "ran analysis");
        when(aiPort.chatFast(anyString(), anyString())).thenReturn("""
                {"targetStack":"Spring Boot 3 + Kafka","steps":["Step 1: Replace PubSub"],\
                "riskLevel":"MEDIUM","estimatedEffort":"3 days","summary":"migrate p1"}""");

        MigrationPlan plan = agent.execute(input);

        assertThat(plan.projectId()).isEqualTo("p1");
        assertThat(plan.storageKey()).isEqualTo("uploads/p1.zip");
        assertThat(plan.targetStack()).isEqualTo("Spring Boot 3 + Kafka");
        assertThat(plan.steps()).containsExactly("Step 1: Replace PubSub");
        assertThat(plan.riskLevel()).isEqualTo("MEDIUM");
        assertThat(plan.estimatedEffort()).isEqualTo("3 days");
        assertThat(plan.summary()).isEqualTo("migrate p1");
    }

    @Test
    void execute_aiFails_fallsBackToBasicPlan() {
        AnalysisReport input = new AnalysisReport(
                "p1", "uploads/p1.zip", List.of(), List.of(), "ran analysis");
        when(aiPort.chatFast(anyString(), anyString())).thenThrow(new RuntimeException("AI down"));

        MigrationPlan plan = agent.execute(input);

        assertThat(plan.projectId()).isEqualTo("p1");
        assertThat(plan.storageKey()).isEqualTo("uploads/p1.zip");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.summary()).contains("ran analysis");
    }

    @Test
    void execute_aiFailsWithBlankSummary_fallbackContainsNoStepsHint() {
        AnalysisReport input = AnalysisReport.empty("p1");
        when(aiPort.chatFast(anyString(), anyString())).thenThrow(new RuntimeException("AI down"));

        MigrationPlan plan = agent.execute(input);

        assertThat(plan.summary()).contains("no steps");
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
