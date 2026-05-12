package com.altrix.orchestrator.domain.model.workflow;

import com.altrix.common.domain.model.*;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed workflow state for the LangGraph4j migration pipeline.
 *
 * <p>Wraps a {@code Map<String,Object>} (required by LangGraph4j's {@link AgentState})
 * and exposes typed accessors for each domain artifact produced by the agent chain:
 *
 * <pre>
 * ProjectContext → AnalysisReport → MigrationPlan → ApprovedPlan
 *               → MigrationArtifact → ValidationReport → MigrationReport
 * </pre>
 *
 * <p>Nodes return a partial-state map (only the keys they produced); LangGraph4j
 * merges that back into the full state before passing it to the next node.
 */
public class MigrationState extends AgentState {

    // ── State key constants ───────────────────────────────────────────────────
    public static final String PROJECT_CONTEXT = "project_context";
    public static final String ANALYSIS_REPORT = "analysis_report";
    public static final String MIGRATION_PLAN = "migration_plan";
    public static final String APPROVED_PLAN = "approved_plan";
    public static final String MIGRATION_ARTIFACT = "migration_artifact";
    public static final String VALIDATION_REPORT = "validation_report";
    public static final String MIGRATION_REPORT = "migration_report";
    public static final String ERROR_HISTORY = "error_history";
    public static final String RETRY_COUNT = "retry_count";
    /** Token-budgeted failure summary injected by RetryContextBuilder (#48). */
    public static final String RETRY_CONTEXT = "retry_context";
    /** True when Agent 2 returned a plan from the similarity cache rather than calling the AI (#155). */
    public static final String CACHE_ASSISTED = "cache_assisted";

    /**
     * Required by {@link org.bsc.langgraph4j.state.AgentStateFactory}.
     */
    public MigrationState(Map<String, Object> initData) {
        super(initData);
    }

    /**
     * Creates the initial state map to bootstrap the graph from a job context.
     */
    public static Map<String, Object> initial(ProjectContext ctx) {
        Map<String, Object> data = new HashMap<>();
        data.put(PROJECT_CONTEXT, ctx);
        data.put(RETRY_COUNT, 0);
        return data;
    }

    // ── Typed accessors ───────────────────────────────────────────────────────

    public Optional<ProjectContext> projectContext() {
        return value(PROJECT_CONTEXT);
    }

    public Optional<AnalysisReport> analysisReport() {
        return value(ANALYSIS_REPORT);
    }

    public Optional<MigrationPlan> migrationPlan() {
        return value(MIGRATION_PLAN);
    }

    public Optional<ApprovedPlan> approvedPlan() {
        return value(APPROVED_PLAN);
    }

    public Optional<MigrationArtifact> migrationArtifact() {
        return value(MIGRATION_ARTIFACT);
    }

    public Optional<ValidationReport> validationReport() {
        return value(VALIDATION_REPORT);
    }

    public Optional<MigrationReport> migrationReport() {
        return value(MIGRATION_REPORT);
    }

    public List<String> errorHistory() {
        return this.<List<String>>value(ERROR_HISTORY).orElse(List.of());
    }

    public int retryCount() {
        return this.<Integer>value(RETRY_COUNT).orElse(0);
    }

    public Optional<String> retryContext() {
        return value(RETRY_CONTEXT);
    }
}
