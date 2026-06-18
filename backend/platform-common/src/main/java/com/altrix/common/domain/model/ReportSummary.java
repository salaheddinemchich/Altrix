package com.altrix.common.domain.model;

import java.io.Serializable;
import java.util.List;

/**
 * Structured, machine-readable companion to {@link MigrationReport#content()}.
 *
 * <p>Every field here is computed deterministically from data the pipeline
 * already produced (file counts, validation findings, blueprint risk notes,
 * recorded migration decisions) — nothing in this record is AI-generated.
 * Returned as-is over the report REST endpoints, so this record IS the
 * "machine-readable JSON report" — no separate serialisation step needed.
 */
public record ReportSummary(

        /** SUCCESS, PARTIAL, or FAILED. */
        String status,

        /** 0-100. See {@code MigrationReportBuilder#computeConfidenceScore}. */
        int confidenceScore,

        int filesAnalyzed,
        int filesModified,
        int filesCreated,
        int filesDeleted,
        int filesUnchanged,

        /** {@code true} when no ERROR finding came from the "docker" runner. */
        boolean compilePassed,
        /** {@code true} when no ERROR finding came from the "docker-boot" runner. */
        boolean bootPassed,
        /** {@code true} when no ERROR finding came from the "docker-test" runner. */
        boolean testsPassed,

        String targetStack,
        String riskLevel,
        String estimatedEffort,

        List<String> detectedComponents,
        List<String> detectedIntegrations,
        List<String> migrationSteps,

        List<String> addedDependencies,
        List<String> removedDependencies,

        List<ValidationStageResult> validationStages,
        List<RiskItem> risks,
        List<String> manualActions,
        List<DecisionLogEntry> decisions,

        /** APPROVE_FOR_DEPLOYMENT, MANUAL_REVIEW_REQUIRED, or DO_NOT_DEPLOY. */
        String recommendation

) implements Serializable {

    public ReportSummary {
        if (status == null) status = "UNKNOWN";
        if (targetStack == null) targetStack = "";
        if (riskLevel == null) riskLevel = "";
        if (estimatedEffort == null) estimatedEffort = "";
        if (recommendation == null) recommendation = "MANUAL_REVIEW_REQUIRED";
        detectedComponents = detectedComponents != null ? List.copyOf(detectedComponents) : List.of();
        detectedIntegrations = detectedIntegrations != null ? List.copyOf(detectedIntegrations) : List.of();
        migrationSteps = migrationSteps != null ? List.copyOf(migrationSteps) : List.of();
        addedDependencies = addedDependencies != null ? List.copyOf(addedDependencies) : List.of();
        removedDependencies = removedDependencies != null ? List.copyOf(removedDependencies) : List.of();
        validationStages = validationStages != null ? List.copyOf(validationStages) : List.of();
        risks = risks != null ? List.copyOf(risks) : List.of();
        manualActions = manualActions != null ? List.copyOf(manualActions) : List.of();
        decisions = decisions != null ? List.copyOf(decisions) : List.of();
    }

    public static ReportSummary empty() {
        return new ReportSummary("UNKNOWN", 0, 0, 0, 0, 0, 0,
                false, false, false, "", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                "MANUAL_REVIEW_REQUIRED");
    }

    /** One sandbox-runner stage's verdict, e.g. ("docker", "Docker Compile", true, 0, 0). */
    public record ValidationStageResult(
            String runnerId, String label, boolean passed, int errorCount, int warningCount
    ) implements Serializable {
    }

    /** One detected risk, surfaced from WARNING findings, blueprint risk notes, or unmapped features. */
    public record RiskItem(
            String level, String file, String issue, String recommendation
    ) implements Serializable {
    }

    /** Mirrors one {@code MigrationDecision} — real recorded data, not AI narrative. */
    public record DecisionLogEntry(
            String kind, String from, String to, String scope, String rationale
    ) implements Serializable {
    }
}
