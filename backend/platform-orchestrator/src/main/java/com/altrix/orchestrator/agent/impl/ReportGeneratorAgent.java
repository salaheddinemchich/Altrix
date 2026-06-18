package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry;
import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.FileProvenanceRepository;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.MigrationDecisionRegistryPort;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintPort;
import com.altrix.orchestrator.infrastructure.report.DependencyDiffAnalyzer;
import com.altrix.orchestrator.infrastructure.report.MigrationReportBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Agent 5 — Report Generator.
 *
 * <p>Produces a fully deterministic migration report — no AI call is made
 * anywhere in this agent. {@link MigrationReportBuilder} renders both the
 * Markdown {@code content} and the structured {@link
 * com.altrix.common.domain.model.ReportSummary} from data the upstream
 * agents already computed (file changes, validation findings) plus
 * best-effort session enrichment (blueprint, recorded migration decisions,
 * doc provenance, pom.xml dependency diff).
 *
 * <p>Each enrichment lookup is independently null-guarded — exactly the
 * pattern {@code CoreMigratorAgent.resolveBlueprint()} uses — so a missing
 * blueprint, decision registry, provenance record, or pom.xml only omits
 * that section of the report; it never blocks report generation.
 */
@Slf4j
@Component("reportGeneratorAgent")
@RequiredArgsConstructor
public class ReportGeneratorAgent implements MigrationAgent<WorkflowOutcome, MigrationReport> {

    private final MigrationReportBuilder reportBuilder;
    private final DependencyDiffAnalyzer dependencyDiffAnalyzer;
    private final ProjectBlueprintPort projectBlueprintPort;
    private final MigrationDecisionRegistryPort migrationDecisionRegistryPort;
    private final FileProvenanceRepository fileProvenanceRepository;
    private final FileReaderPort fileReader;

    @Override
    public String getName() {
        return "Report Generator";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public MigrationReport execute(WorkflowOutcome input) {
        if (input == null) throw new AgentFailureException(getName(), "input WorkflowOutcome was null");
        log.info("[{}] generating report for project '{}'", getName(), input.projectId());

        WorkflowSessionId sessionId = resolveSessionId();
        ProjectBlueprint blueprint = resolveBlueprint(sessionId);
        MigrationDecisionRegistry decisions = resolveDecisions(sessionId);
        FileProvenance provenance = resolveProvenance(sessionId);
        DependencyDiffAnalyzer.Result dependencyDiff = resolveDependencyDiff(input);

        MigrationReportBuilder.Output output = reportBuilder.build(
                new MigrationReportBuilder.Input(input, blueprint, decisions, provenance, dependencyDiff));

        return new MigrationReport(input.projectId(), output.markdown(), Instant.now(), output.summary());
    }

    private WorkflowSessionId resolveSessionId() {
        String sessionId = SandboxContext.currentSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            return WorkflowSessionId.of(sessionId);
        } catch (Exception e) {
            log.warn("[{}] invalid sessionId in SandboxContext: {}", getName(), e.getMessage());
            return null;
        }
    }

    private ProjectBlueprint resolveBlueprint(WorkflowSessionId sessionId) {
        if (sessionId == null) return null;
        try {
            return projectBlueprintPort.findForSession(sessionId).orElse(null);
        } catch (Exception e) {
            log.debug("[{}] blueprint lookup skipped: {}", getName(), e.getMessage());
            return null;
        }
    }

    private MigrationDecisionRegistry resolveDecisions(WorkflowSessionId sessionId) {
        if (sessionId == null) return null;
        try {
            return migrationDecisionRegistryPort.findForSession(sessionId);
        } catch (Exception e) {
            log.debug("[{}] decision registry lookup skipped: {}", getName(), e.getMessage());
            return null;
        }
    }

    private FileProvenance resolveProvenance(WorkflowSessionId sessionId) {
        if (sessionId == null) return null;
        try {
            return fileProvenanceRepository.findBySessionId(sessionId).orElse(null);
        } catch (Exception e) {
            log.debug("[{}] provenance lookup skipped: {}", getName(), e.getMessage());
            return null;
        }
    }

    private DependencyDiffAnalyzer.Result resolveDependencyDiff(WorkflowOutcome outcome) {
        try {
            String storageKey = outcome.plan() != null ? outcome.plan().storageKey() : null;
            if (storageKey == null || storageKey.isBlank()) return DependencyDiffAnalyzer.Result.EMPTY;
            String originalPom = fileReader.readSingleFile(storageKey, "pom.xml");
            String migratedPom = outcome.artifact().files().stream()
                    .filter(f -> "pom.xml".equals(f.newPath()))
                    .map(MigratedFile::content)
                    .findFirst().orElse(null);
            return dependencyDiffAnalyzer.diff(originalPom, migratedPom);
        } catch (Exception e) {
            log.debug("[{}] dependency diff skipped: {}", getName(), e.getMessage());
            return DependencyDiffAnalyzer.Result.EMPTY;
        }
    }
}
