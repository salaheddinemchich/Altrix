package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.service.PlanSimilarityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MigrationPlannerAgentTest {

    @Mock AiPort aiPort;
    @Mock PlanSimilarityService planSimilarityService;
    @Mock FileReaderPort fileReader;
    @InjectMocks MigrationPlannerAgent agent;

    @BeforeEach
    void cacheMiss() {
        // Default: no similarity cache hit — let the AI call proceed
        when(planSimilarityService.findSimilar(any())).thenReturn(Optional.empty());
    }

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
        verify(planSimilarityService).store(any(), any());
    }

    @Test
    void execute_similarityCacheHit_returnsCachedPlan_skipsAi() {
        AnalysisReport input = new AnalysisReport(
                "p1", "uploads/p1.zip", List.of("c1"), List.of("Google Pub/Sub"), "ran analysis");
        MigrationPlan cached = new MigrationPlan("p1", "", "Spring Boot 3 + Kafka",
                List.of("Step 1"), "LOW", "1 day", "cached plan", List.of());
        when(planSimilarityService.findSimilar(input)).thenReturn(Optional.of(cached));

        MigrationPlan plan = agent.execute(input);

        assertThat(plan).isSameAs(cached);
        // AI must not be called
        verify(aiPort, org.mockito.Mockito.never()).chatFast(anyString(), anyString());
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

    @Test
    void execute_filtersTargetFilesNotPresentInSourceZip() {
        AnalysisReport input = new AnalysisReport(
                "p1", "uploads/p1.zip", List.of("c1"), List.of("i1"), "ran analysis");
        // AI proposes both a real file and a hallucinated one
        when(aiPort.chatFast(anyString(), anyString())).thenReturn("""
                {"targetStack":"Spring Boot 3 + Kafka","steps":["Step 1"],\
                "riskLevel":"LOW","estimatedEffort":"1d","summary":"s",\
                "targetFiles":["src/main/java/Real.java","application.yml"]}""");
        when(fileReader.listAllPaths("uploads/p1.zip"))
                .thenReturn(java.util.Set.of("src/main/java/Real.java", "pom.xml"));

        MigrationPlan plan = agent.execute(input);

        assertThat(plan.targetFiles()).containsExactly("src/main/java/Real.java");
    }
}
