package com.altrix.common.domain.model;

/**
 * Aggregate fed to {@code ReportGeneratorAgent} (Agent 5).
 *
 * <p>Bundles every artifact produced by the upstream agents so the report
 * generator can summarise the entire workflow in one pass without
 * reaching back into the orchestrator's state.
 */
public record WorkflowOutcome(

        String projectId,
        AnalysisReport     analysis,
        MigrationPlan      plan,
        MigrationArtifact  artifact,
        ValidationReport   validation

) {
    public WorkflowOutcome {
        if (projectId  == null) projectId  = "";
        if (analysis   == null) analysis   = AnalysisReport.empty(projectId);
        if (plan       == null) plan       = MigrationPlan.empty(projectId);
        if (artifact   == null) artifact   = MigrationArtifact.empty(projectId);
        if (validation == null) validation = ValidationReport.pending(projectId);
    }
}
