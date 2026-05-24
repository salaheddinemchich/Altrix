package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.*;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Agent 5 — Report Generator.
 *
 * <p>Produces a structured Markdown report from the complete
 * {@link WorkflowOutcome}: analysis summary, migration plan with steps,
 * files-changed table, validation verdict.  The deterministic template
 * is always emitted; an optional AI-narrated executive summary (#160) is
 * prepended when {@code migration.report.ai-narrative.enabled=true}
 * (the default).
 *
 * <p>The AI narrative is <b>best-effort</b>: if the call fails, returns
 * empty, or throws, the deterministic template still ships.  The user
 * never sees a "report unavailable" failure just because the LLM
 * blinked.
 */
@Slf4j
@Component("reportGeneratorAgent")
public class ReportGeneratorAgent implements MigrationAgent<WorkflowOutcome, MigrationReport> {

    private static final String NARRATIVE_SYSTEM_PROMPT = """
            You are a senior software architect writing a 2–4 sentence
            executive summary of a Java codebase migration from Google
            Cloud Pub/Sub to Apache Kafka.

            The summary must:
            - State whether the migration succeeded (validation passed)
              or has open issues (validation failed) in the FIRST sentence.
            - Mention the rough scope (number of files changed / risk
              level) without inventing numbers.
            - Be plain prose — no markdown, no headers, no bullets.
            - Address the reader as the engineering team owning the
              source project.

            Return ONLY the executive-summary text.  No preamble, no
            closing remark, no labels.
            """;

    private final AiPort aiPort;
    private final boolean narrativeEnabled;

    public ReportGeneratorAgent(
            AiPort aiPort,
            @Value("${migration.report.ai-narrative.enabled:true}") boolean narrativeEnabled) {
        this.aiPort = aiPort;
        this.narrativeEnabled = narrativeEnabled;
    }

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
        return new MigrationReport(input.projectId(), buildMarkdown(input), Instant.now());
    }

    private String buildMarkdown(WorkflowOutcome outcome) {
        AnalysisReport analysis = outcome.analysis();
        MigrationPlan plan = outcome.plan();
        MigrationArtifact artifact = outcome.artifact();
        ValidationReport validation = outcome.validation();

        long modified = artifact.files().stream().filter(f -> f.changeType() == FileChangeType.MODIFIED).count();
        long unchanged = artifact.files().stream().filter(f -> f.changeType() == FileChangeType.UNCHANGED).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# Migration Report\n\n");

        // #160 — AI-narrated executive summary.  Best-effort: skip the
        // section on any error so a flaky provider can't block the
        // template path that everyone relies on.
        if (narrativeEnabled) {
            String narrative = buildAiNarrative(outcome, modified);
            if (narrative != null && !narrative.isBlank()) {
                sb.append("## Executive summary\n\n")
                  .append(narrative.trim()).append("\n\n");
            }
        }

        sb.append("| Field | Value |\n|-------|-------|\n");
        sb.append("| **Project** | ").append(outcome.projectId()).append(" |\n");
        sb.append("| **Generated** | ").append(Instant.now()).append(" |\n");
        if (!plan.targetStack().isBlank()) sb.append("| **Target stack** | ").append(plan.targetStack()).append(" |\n");
        if (!plan.riskLevel().isBlank()) sb.append("| **Risk level** | ").append(plan.riskLevel()).append(" |\n");
        if (!plan.estimatedEffort().isBlank())
            sb.append("| **Effort** | ").append(plan.estimatedEffort()).append(" |\n");
        sb.append("\n");

        if (analysis != null) {
            sb.append("## Analysis\n\n");
            if (!analysis.summary().isBlank()) sb.append(analysis.summary()).append("\n\n");
            if (!analysis.detectedComponents().isEmpty()) {
                sb.append("**Components**: ").append(String.join(", ", analysis.detectedComponents())).append("\n");
            }
            if (!analysis.detectedIntegrations().isEmpty()) {
                sb.append("**Integrations**: ").append(String.join(", ", analysis.detectedIntegrations())).append("\n");
            }
            sb.append("\n");
        }

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

    /**
     * Calls the analysis-tier model with a tight user-content payload —
     * just the verdict and a few counters, NOT the full file list (which
     * would blow the context budget for projects with thousands of
     * files).  Returns the narrative text, or null on any failure.
     */
    private String buildAiNarrative(WorkflowOutcome outcome, long modifiedCount) {
        try {
            MigrationPlan plan = outcome.plan();
            ValidationReport v = outcome.validation();
            String userContent = """
                    Project: %s
                    Target stack: %s
                    Risk level: %s
                    Estimated effort: %s
                    Files modified: %d
                    Validation: %s
                    Validation issues: %d
                    """.formatted(
                    outcome.projectId(),
                    plan.targetStack(),
                    plan.riskLevel(),
                    plan.estimatedEffort(),
                    modifiedCount,
                    v.passed() ? "PASSED" : "FAILED",
                    v.failures().size()
            );
            // Analysis tier — cheaper / faster than the migration tier; this
            // is a single short text generation, not a code rewrite.
            String narrative = aiPort.chatFast(NARRATIVE_SYSTEM_PROMPT, userContent);
            return narrative != null ? narrative.strip() : null;
        } catch (Exception e) {
            log.warn("[{}] AI narrative skipped — using template-only report: {}",
                    getName(), e.getMessage());
            return null;
        }
    }
}
