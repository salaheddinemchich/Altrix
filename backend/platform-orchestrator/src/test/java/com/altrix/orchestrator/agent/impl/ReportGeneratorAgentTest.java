package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.*;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.FileProvenanceRepository;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.MigrationDecisionRegistryPort;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintPort;
import com.altrix.orchestrator.infrastructure.report.DependencyDiffAnalyzer;
import com.altrix.orchestrator.infrastructure.report.MigrationReportBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * No AiPort dependency exists anywhere in this test suite by design —
 * {@link ReportGeneratorAgent} makes zero AI calls.  Coverage here is
 * about the agent's WIRING (session resolution, null-guarded enrichment
 * lookups); section-content assertions live in
 * {@code MigrationReportBuilderTest}.
 */
class ReportGeneratorAgentTest {

    private final ProjectBlueprintPort blueprintPort = Mockito.mock(ProjectBlueprintPort.class);
    private final MigrationDecisionRegistryPort decisionPort = Mockito.mock(MigrationDecisionRegistryPort.class);
    private final FileProvenanceRepository provenanceRepo = Mockito.mock(FileProvenanceRepository.class);
    private final FileReaderPort fileReader = Mockito.mock(FileReaderPort.class);

    private final ReportGeneratorAgent agent = new ReportGeneratorAgent(
            new MigrationReportBuilder(), new DependencyDiffAnalyzer(),
            blueprintPort, decisionPort, provenanceRepo, fileReader);

    @AfterEach
    void clearContext() {
        SandboxContext.clear();
    }

    private WorkflowOutcome outcome() {
        return new WorkflowOutcome("p1",
                AnalysisReport.empty("p1"),
                new MigrationPlan("p1", "", "Spring Boot 3 + Kafka", List.of("Step 1: replace PubSub"),
                        "MEDIUM", "3 days", "the plan", List.of()),
                MigrationArtifact.empty("p1"),
                ValidationReport.pending("p1"));
    }

    @Test
    void exposesNameAndOrder5() {
        assertThat(agent.getName()).isEqualTo("Report Generator");
        assertThat(agent.getOrder()).isEqualTo(5);
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }

    @Test
    void execute_noSessionIdInContext_stillProducesReport() {
        // SandboxContext never set — agent must not throw or block.
        MigrationReport report = agent.execute(outcome());

        assertThat(report.projectId()).isEqualTo("p1");
        assertThat(report.content()).contains("# Migration Report").contains("the plan");
        assertThat(report.summary()).isNotNull();
        Mockito.verifyNoInteractions(blueprintPort, decisionPort, provenanceRepo);
    }

    @Test
    void execute_withSessionId_fetchesEnrichmentData() {
        String sessionId = "11111111-1111-1111-1111-111111111111";
        SandboxContext.setSessionId(sessionId);
        when(blueprintPort.findForSession(any())).thenReturn(Optional.empty());
        when(decisionPort.findForSession(any()))
                .thenReturn(com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry.empty(sessionId));
        when(provenanceRepo.findBySessionId(any())).thenReturn(Optional.empty());

        agent.execute(outcome());

        Mockito.verify(blueprintPort).findForSession(WorkflowSessionId.of(sessionId));
        Mockito.verify(decisionPort).findForSession(WorkflowSessionId.of(sessionId));
        Mockito.verify(provenanceRepo).findBySessionId(WorkflowSessionId.of(sessionId));
    }

    @Test
    void execute_enrichmentLookupThrows_stillProducesReport() {
        SandboxContext.setSessionId("11111111-1111-1111-1111-111111111111");
        when(blueprintPort.findForSession(any())).thenThrow(new RuntimeException("db unavailable"));
        when(decisionPort.findForSession(any())).thenThrow(new RuntimeException("db unavailable"));
        when(provenanceRepo.findBySessionId(any())).thenThrow(new RuntimeException("db unavailable"));

        MigrationReport report = agent.execute(outcome());

        assertThat(report.content()).contains("# Migration Report");
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
}
