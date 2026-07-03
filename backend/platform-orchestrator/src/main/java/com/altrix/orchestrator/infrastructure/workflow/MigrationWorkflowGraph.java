package com.altrix.orchestrator.infrastructure.workflow;

import com.altrix.common.domain.model.*;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.model.workflow.MigrationState;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowExecutionPort;
import com.altrix.orchestrator.infrastructure.ai.RetryContextBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.altrix.orchestrator.domain.model.workflow.MigrationState.*;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LangGraph4j stateful migration workflow graph (#173).
 *
 * <p>Implements {@link WorkflowExecutionPort} so the domain service never
 * imports LangGraph4j types directly.
 *
 * <p>Graph topology:
 * <pre>
 *  START
 *   └─► context-analyzer
 *         ├─► [no integrations found] ──► END   (no-op: nothing to migrate)
 *         └─► migration-planner
 *               └─► core-migrator ◄──────────────────────────┐
 *                     ├─► [empty artifact, retry &lt; 3]        │ (error retry)
 *                     └─► sandbox-validator                  │
 *                           ├─► [failed, retry &lt; 3] ─────────┘ (#48 validation retry)
 *                           └─► report-generator
 *                                 └─► END
 * </pre>
 *
 * <p>When validation fails and retries remain, {@link RetryContextBuilder} produces
 * a token-budgeted failure summary that is prepended to Agent 3's system prompt (#48).
 *
 * <p>After each node a WebSocket progress event is emitted.
 * Checkpoints are persisted via the injected {@link BaseCheckpointSaver}
 * (default: in-memory; swap for {@code RedisGraphCheckpointAdapter} in prod).
 */
@Slf4j
@RequiredArgsConstructor
public class MigrationWorkflowGraph implements WorkflowExecutionPort {

    // ── node identifiers ─────────────────────────────────────────────────────
    static final String NODE_CONTEXT_ANALYZER = "context-analyzer";
    static final String NODE_MIGRATION_PLANNER = "migration-planner";
    static final String NODE_CORE_MIGRATOR = "core-migrator";
    static final String NODE_SEMANTIC_VALIDATOR = "semantic-validator";
    static final String NODE_SANDBOX_VALIDATOR = "sandbox-validator";
    static final String NODE_REPORT_GENERATOR = "report-generator";

    static final int MAX_RETRIES = 3;

    private final MigrationAgent<ProjectContext, AnalysisReport> contextAnalyzer;
    private final MigrationAgent<AnalysisReport, MigrationPlan> planner;
    private final MigrationAgent<ApprovedPlan, MigrationArtifact> migrator;
    /** Verifies the artifact semantically between migrator and sandbox. */
    private final MigrationAgent<MigrationArtifact, MigrationArtifact> semanticValidator;
    private final MigrationAgent<MigrationArtifact, ValidationReport> validator;
    private final MigrationAgent<WorkflowOutcome, MigrationReport> reporter;
    private final ProgressNotifierPort progressNotifier;
    private final BaseCheckpointSaver checkpointSaver;
    private final RetryContextBuilder retryContextBuilder;
    /**
     * #10 — when true, the graph halts at END after the planner so a human can
     * review (and optionally edit) the plan via the AWAITING_APPROVAL gate.
     * The continuation runs migrator → validator → reporter via
     * {@code ResumeMigrationUseCase}.  When false, the planner auto-approves
     * its own plan and the graph runs to completion in one shot (legacy path
     * preserved for tests / batch mode).
     */
    private final boolean requireApproval;

    /**
     * Lazily compiled graph — built once on first call and reused thereafter.
     */
    private volatile CompiledGraph<MigrationState> compiledGraph;

    // ── WorkflowExecutionPort ────────────────────────────────────────────────

    @Override
    public MigrationState execute(ProjectContext context) {
        CompiledGraph<MigrationState> graph = compiledGraph();

        RunnableConfig config = RunnableConfig.builder()
                .threadId(context.jobId())
                .build();

        Optional<MigrationState> result = graph.invoke(
                MigrationState.initial(context), config);

        return result.orElseThrow(() ->
                new AgentFailureException("MigrationWorkflowGraph",
                        "Graph invocation returned no state for job " + context.jobId()));
    }

    // ── Graph construction ───────────────────────────────────────────────────

    private CompiledGraph<MigrationState> compiledGraph() {
        if (compiledGraph == null) {
            synchronized (this) {
                if (compiledGraph == null) {
                    compiledGraph = buildGraph();
                }
            }
        }
        return compiledGraph;
    }

    private CompiledGraph<MigrationState> buildGraph() {
        try {
            StateGraph<MigrationState> graph = new StateGraph<>(
                    new ObjectStreamStateSerializer<>(MigrationState::new));

            graph.addNode(NODE_CONTEXT_ANALYZER, nodeAction(this::runContextAnalyzer))
                    .addNode(NODE_MIGRATION_PLANNER, nodeAction(this::runMigrationPlanner))
                    .addNode(NODE_CORE_MIGRATOR, nodeAction(this::runCoreMigrator))
                    .addNode(NODE_SEMANTIC_VALIDATOR, nodeAction(this::runSemanticValidator))
                    .addNode(NODE_SANDBOX_VALIDATOR, nodeAction(this::runSandboxValidator))
                    .addNode(NODE_REPORT_GENERATOR, nodeAction(this::runReportGenerator));

            graph.addEdge(START, NODE_CONTEXT_ANALYZER)
                    .addConditionalEdges(
                            NODE_CONTEXT_ANALYZER,
                            edgeAction(this::routeAfterAnalysis),
                            Map.of(NODE_MIGRATION_PLANNER, NODE_MIGRATION_PLANNER, END, END))
                    .addConditionalEdges(
                            NODE_MIGRATION_PLANNER,
                            edgeAction(this::routeAfterPlanning),
                            Map.of(NODE_CORE_MIGRATOR, NODE_CORE_MIGRATOR, END, END))
                    // Migrator routes to the SEMANTIC validator (not sandbox)
                    // on success; still retries itself on an empty artifact.
                    .addConditionalEdges(
                            NODE_CORE_MIGRATOR,
                            edgeAction(this::routeAfterMigration),
                            Map.of(NODE_CORE_MIGRATOR, NODE_CORE_MIGRATOR,
                                    NODE_SEMANTIC_VALIDATOR, NODE_SEMANTIC_VALIDATOR))
                    // Semantic validator always feeds the sandbox compile next.
                    .addEdge(NODE_SEMANTIC_VALIDATOR, NODE_SANDBOX_VALIDATOR)
                    .addConditionalEdges(
                            NODE_SANDBOX_VALIDATOR,
                            edgeAction(this::routeAfterValidation),
                            Map.of(NODE_CORE_MIGRATOR, NODE_CORE_MIGRATOR,
                                    NODE_REPORT_GENERATOR, NODE_REPORT_GENERATOR))
                    .addEdge(NODE_REPORT_GENERATOR, END);

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .build());

        } catch (Exception e) {
            throw new IllegalStateException("Failed to build MigrationWorkflowGraph", e);
        }
    }

    // ── Node actions ─────────────────────────────────────────────────────────

    private Map<String, Object> runContextAnalyzer(MigrationState state) {
        ProjectContext ctx = requireContext(state, NODE_CONTEXT_ANALYZER);

        notifyRunning(ctx.jobId(), "Context Analyzer");
        AnalysisReport report = contextAnalyzer.execute(ctx);
        notifyDone(ctx.jobId(), "Context Analyzer");

        log.info("[{}] job='{}' — {} components, {} integrations",
                NODE_CONTEXT_ANALYZER, ctx.jobId(),
                report.detectedComponents().size(), report.detectedIntegrations().size());

        return Map.of(ANALYSIS_REPORT, report);
    }

    private Map<String, Object> runMigrationPlanner(MigrationState state) {
        ProjectContext ctx = requireContext(state, NODE_MIGRATION_PLANNER);
        AnalysisReport input = state.analysisReport()
                .orElseThrow(() -> new AgentFailureException(NODE_MIGRATION_PLANNER,
                        "AnalysisReport missing from state"));

        notifyRunning(ctx.jobId(), "Migration Planner");
        MigrationPlan plan = planner.execute(input);
        notifyDone(ctx.jobId(), "Migration Planner");

        // #10 — when the approval gate is on we deliberately do NOT inject an
        // APPROVED_PLAN.  routeAfterPlanning sees the missing slot and halts at
        // END so a human can review (and edit) the plan via the session API.
        // When off, the planner auto-approves and the graph runs to completion.
        if (requireApproval) {
            return Map.of(MIGRATION_PLAN, plan);
        }
        return Map.of(MIGRATION_PLAN, plan, APPROVED_PLAN, ApprovedPlan.autoApproved(plan));
    }

    /**
     * Conditional edge after the planner: continue to the migrator only when an
     * {@link ApprovedPlan} is present in the state; otherwise halt at END and
     * wait for {@code HandleApprovalUseCase.approve(...)} to kick off the
     * continuation (#10).
     */
    private String routeAfterPlanning(MigrationState state) {
        return state.approvedPlan().isPresent() ? NODE_CORE_MIGRATOR : END;
    }

    private Map<String, Object> runCoreMigrator(MigrationState state) {
        ProjectContext ctx = requireContext(state, NODE_CORE_MIGRATOR);
        ApprovedPlan approved = state.approvedPlan()
                .orElseThrow(() -> new AgentFailureException(NODE_CORE_MIGRATOR,
                        "ApprovedPlan missing from state"));

        // Inject retry context and previous artifact so the migrator starts from
        // the last attempt's output instead of the original source (#checkpoint).
        // The failing paths from the previous validation narrow the retry's LLM
        // pass to the implicated files — everything else is kept verbatim from
        // the checkpoint so the model can't corrupt already-correct output.
        String retryCtx = state.retryContext().orElse(null);
        if (retryCtx != null && !retryCtx.isBlank()) {
            MigrationArtifact prevArtifact = state.migrationArtifact().orElse(null);
            List<String> failingPaths = state.validationReport()
                    .map(ValidationReport::failingFilePaths)
                    .orElse(List.of());
            approved = approved.withRetryContext(retryCtx, prevArtifact, failingPaths);
        }

        notifyRunning(ctx.jobId(), "Core Migrator");
        try {
            MigrationArtifact artifact = migrator.execute(approved);
            notifyDone(ctx.jobId(), "Core Migrator");
            return Map.of(MIGRATION_ARTIFACT, artifact);

        } catch (AiProviderUnavailableException e) {
            // Propagate immediately — outer OrchestratorService handles graceful degradation
            throw e;
        } catch (Exception e) {
            List<String> history = new ArrayList<>(state.errorHistory());
            history.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            int retries = state.retryCount() + 1;
            notifyFailed(ctx.jobId(), "Core Migrator",
                    "Attempt " + retries + "/" + MAX_RETRIES + ": " + e.getMessage());
            log.warn("[{}] job='{}' attempt {}/{} failed: {}",
                    NODE_CORE_MIGRATOR, ctx.jobId(), retries, MAX_RETRIES, e.getMessage());
            return Map.of(ERROR_HISTORY, history, RETRY_COUNT, retries);
        }
    }

    private Map<String, Object> runSemanticValidator(MigrationState state) {
        ProjectContext ctx = requireContext(state, NODE_SEMANTIC_VALIDATOR);
        MigrationArtifact artifact = state.migrationArtifact()
                .orElseThrow(() -> new AgentFailureException(NODE_SEMANTIC_VALIDATOR,
                        "MigrationArtifact missing from state"));

        notifyRunning(ctx.jobId(), "Semantic Validator");
        // Stage 3: report-only — returns the artifact unchanged.  Later
        // stages return a repaired artifact, which is why we write it back
        // into MIGRATION_ARTIFACT so the sandbox sees the latest version.
        MigrationArtifact validated = semanticValidator.execute(artifact);
        notifyDone(ctx.jobId(), "Semantic Validator");

        return Map.of(MIGRATION_ARTIFACT, validated);
    }

    private Map<String, Object> runSandboxValidator(MigrationState state) {
        ProjectContext ctx = requireContext(state, NODE_SANDBOX_VALIDATOR);
        MigrationArtifact artifact = state.migrationArtifact()
                .orElseThrow(() -> new AgentFailureException(NODE_SANDBOX_VALIDATOR,
                        "MigrationArtifact missing from state"));

        notifyRunning(ctx.jobId(), "Sandbox Validator");
        ValidationReport report = validator.execute(artifact);
        notifyDone(ctx.jobId(), "Sandbox Validator");

        Map<String, Object> result = new HashMap<>();
        result.put(VALIDATION_REPORT, report);

        if (!report.passed()) {
            // Build token-budgeted retry context so Agent 3 can fix the issues (#48)
            String retryCtx = retryContextBuilder.build(report);
            result.put(RETRY_CONTEXT, retryCtx);
            result.put(RETRY_COUNT, state.retryCount() + 1);
            log.info("[{}] job='{}' validation failed ({} issue(s)), retry {}/{}",
                    NODE_SANDBOX_VALIDATOR, ctx.jobId(),
                    report.failures().size(), state.retryCount() + 1, MAX_RETRIES);
        }

        return result;
    }

    private Map<String, Object> runReportGenerator(MigrationState state) {
        ProjectContext ctx = requireContext(state, NODE_REPORT_GENERATOR);

        WorkflowOutcome outcome = new WorkflowOutcome(
                ctx.projectId(),
                state.analysisReport().orElse(null),
                state.migrationPlan().orElse(null),
                state.migrationArtifact().orElse(null),
                state.validationReport().orElse(null));

        notifyRunning(ctx.jobId(), "Report Generator");
        MigrationReport report = reporter.execute(outcome);
        notifyDone(ctx.jobId(), "Report Generator");

        return Map.of(MIGRATION_REPORT, report);
    }

    // ── Edge conditions ───────────────────────────────────────────────────────

    private String routeAfterAnalysis(MigrationState state) {
        boolean hasIntegrations = state.analysisReport()
                .map(r -> !r.detectedIntegrations().isEmpty())
                .orElse(false);
        String next = hasIntegrations ? NODE_MIGRATION_PLANNER : END;
        if (!hasIntegrations) {
            state.projectContext().ifPresent(ctx ->
                    progressNotifier.notify(ctx.jobId(), "Router",
                            "DONE", "No integrations detected — skipping migration pipeline"));
        }
        return next;
    }

    private String routeAfterMigration(MigrationState state) {
        boolean artifactEmpty = state.migrationArtifact()
                .map(a -> a.files().isEmpty())
                .orElse(true);
        boolean canRetry = state.retryCount() < MAX_RETRIES;
        // On a non-empty artifact, route through the semantic validator
        // (which then unconditionally feeds the sandbox); retry the
        // migrator only when the artifact came back empty.
        return (artifactEmpty && canRetry) ? NODE_CORE_MIGRATOR : NODE_SEMANTIC_VALIDATOR;
    }

    /**
     * Routes after sandbox validation: retries Agent 3 with failure context
     * when validation failed and retries remain (#48); otherwise proceeds to report.
     */
    private String routeAfterValidation(MigrationState state) {
        boolean passed = state.validationReport()
                .map(ValidationReport::passed)
                .orElse(true);
        boolean canRetry = state.retryCount() < MAX_RETRIES;

        if (!passed && canRetry) {
            state.projectContext().ifPresent(ctx ->
                    progressNotifier.notify(ctx.jobId(), "Router",
                            "RUNNING", "Validation failed — retrying migrator with targeted context"));
            return NODE_CORE_MIGRATOR;
        }
        return NODE_REPORT_GENERATOR;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AsyncNodeAction<MigrationState> nodeAction(
            org.bsc.langgraph4j.action.NodeAction<MigrationState> sync) {
        return node_async(sync);
    }

    private static AsyncEdgeAction<MigrationState> edgeAction(
            org.bsc.langgraph4j.action.EdgeAction<MigrationState> sync) {
        return edge_async(sync);
    }

    private ProjectContext requireContext(MigrationState state, String nodeName) {
        return state.projectContext()
                .orElseThrow(() -> new AgentFailureException(nodeName,
                        "ProjectContext missing from workflow state"));
    }

    private void notifyRunning(String jobId, String agent) {
        progressNotifier.notify(jobId, agent, "RUNNING", null);
    }

    private void notifyDone(String jobId, String agent) {
        progressNotifier.notify(jobId, agent, "DONE", null);
    }

    private void notifyFailed(String jobId, String agent, String message) {
        progressNotifier.notify(jobId, agent, "FAILED", message);
    }
}
