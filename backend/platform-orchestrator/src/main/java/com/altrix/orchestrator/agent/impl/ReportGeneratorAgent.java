package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Agent 5 — Report Generator.
 *
 * <p>Produces a structured Markdown report from the complete {@link WorkflowOutcome}:
 * analysis summary, migration plan with steps, files-changed table, and validation verdict.
 */
@Slf4j
@Component("reportGeneratorAgent")
public class ReportGeneratorAgent implements MigrationAgent<WorkflowOutcome, MigrationReport> {

    @Override public String getName()  { return "Report Generator"; }
    @Override public int getOrder() { return 5; }

    @Override
    public MigrationReport execute(WorkflowOutcome input) {
        if (input == null) throw new AgentFailureException(getName(), "input WorkflowOutcome was null");
        log.info("[{}] generating report for project '{}'", getName(), input.projectId());
        return new MigrationReport(input.projectId(), buildMarkdown(input), Instant.now());
    }

    private String buildMarkdown(WorkflowOutcome outcome) {
        AnalysisReport   analysis   = outcome.analysis();
        MigrationPlan    plan       = outcome.plan();
        MigrationArtifact artifact  = outcome.artifact();
        ValidationReport validation = outcome.validation();

        long modified  = artifact.files().stream().filter(f -> f.changeType() == FileChangeType.MODIFIED).count();
        long unchanged = artifact.files().stream().filter(f -> f.changeType() == FileChangeType.UNCHANGED).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# Migration Report\n\n");
        sb.append("| Field | Value |\n|-------|-------|\n");
        sb.append("| **Project** | ").append(outcome.projectId()).append(" |\n");
        sb.append("| **Generated** | ").append(Instant.now()).append(" |\n");
        if (!plan.targetStack().isBlank()) sb.append("| **Target stack** | ").append(plan.targetStack()).append(" |\n");
        if (!plan.riskLevel().isBlank())   sb.append("| **Risk level** | ").append(plan.riskLevel()).append(" |\n");
        if (!plan.estimatedEffort().isBlank()) sb.append("| **Effort** | ").append(plan.estimatedEffort()).append(" |\n");
        sb.append("\n");

        sb.append("## Analysis\n\n");
        if (!analysis.summary().isBlank()) sb.append(analysis.summary()).append("\n\n");
        if (!analysis.detectedComponents().isEmpty()) {
            sb.append("**Components**: ").append(String.join(", ", analysis.detectedComponents())).append("\n");
        }
        if (!analysis.detectedIntegrations().isEmpty()) {
            sb.append("**Integrations**: ").append(String.join(", ", analysis.detectedIntegrations())).append("\n");
        }
        sb.append("\n");

        sb.append("## Migration Plan\n\n");
        if (!plan.summary().isBlank()) sb.append(plan.summary()).append("\n\n");
        if (!plan.steps().isEmpty()) {
            plan.steps().forEach(step -> sb.append("- ").append(step).append("\n"));
            sb.append("\n");
        }

        sb.append("## Files Changed\n\n");
        if (artifact.files().isEmpty()) {
            sb.append("_No files processed._\n\n");
        } else {
            sb.append("| File | Change |\n|------|--------|\n");
            artifact.files().forEach(f ->
                    sb.append("| `").append(f.newPath()).append("` | ").append(f.changeType()).append(" |\n"));
            sb.append("\n**Summary**: ").append(modified).append(" modified, ")
              .append(unchanged).append(" unchanged\n\n");
        }

        sb.append("## Validation\n\n");
        sb.append("**Result**: ").append(validation.passed() ? "PASSED ✓" : "FAILED ✗").append("\n\n");
        if (!validation.failures().isEmpty()) {
            sb.append("**Issues**:\n");
            validation.failures().forEach(f -> sb.append("- ").append(f).append("\n"));
            sb.append("\n");
        }
        sb.append("_").append(validation.summary()).append("_\n");

        return sb.toString();
    }
}
