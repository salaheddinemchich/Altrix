package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.workflow.MigrationState;
import com.altrix.orchestrator.domain.port.in.RunPipelineUseCase;
import com.altrix.orchestrator.domain.port.out.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the full migration pipeline for a single job (#35).
 *
 * <p>Phase 0 — RAG: indexes uploaded source files into the vector store so agents
 * can retrieve semantically relevant chunks instead of receiving the entire codebase.
 *
 * <p>Phase 1–5 — Typed agent workflow: delegates to {@link WorkflowExecutionPort}
 * which drives the five typed {@code MigrationAgent<I,O>} implementations through
 * a LangGraph4j stateful graph with conditional routing, per-step checkpointing,
 * and bounded retry on the Core Migrator node (#173).
 *
 * <p>Graceful degradation: if all AI providers are unavailable and a cached plan
 * exists for this project, it is served instead of failing the job.
 */
@Slf4j
public class OrchestratorService implements RunPipelineUseCase {

    private final WorkflowExecutionPort workflowExecution;
    private final JobStatusUpdatePort jobStatusUpdatePort;
    private final MigratedFileStoragePort migratedFileStoragePort;
    private final ProgressNotifierPort progressNotifierPort;
    private final CodeIndexingPort codeIndexingPort;
    private final MigrationPlanCachePort planCachePort;
    private final WorkflowSessionRepository sessionRepository;
    private final int autoPauseThreshold;

    public OrchestratorService(
            WorkflowExecutionPort workflowExecution,
            JobStatusUpdatePort jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort progressNotifierPort,
            CodeIndexingPort codeIndexingPort,
            MigrationPlanCachePort planCachePort,
            WorkflowSessionRepository sessionRepository,
            int autoPauseThreshold
    ) {
        this.workflowExecution = workflowExecution;
        this.jobStatusUpdatePort = jobStatusUpdatePort;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.progressNotifierPort = progressNotifierPort;
        this.codeIndexingPort = codeIndexingPort;
        this.planCachePort = planCachePort;
        this.sessionRepository = sessionRepository;
        this.autoPauseThreshold = autoPauseThreshold;
        log.info("OrchestratorService initialised — typed LangGraph4j workflow (auto-pause threshold={})",
                autoPauseThreshold);
    }

    @Override
    public ProjectContext run(ProjectContext initial) {
        String jobId = initial.jobId();
        log.info("Starting pipeline for job '{}'", jobId);

        // Re-use an existing session when retrying after auto-pause; create one otherwise.
        WorkflowSession session = sessionRepository.findByJobId(jobId)
                .filter(s -> !s.status().isTerminal())
                .orElseGet(() -> WorkflowSession.create(jobId, initial.projectId()));
        session = sessionRepository.save(session);

        try {
            // ── Phase 0: RAG indexing ────────────────────────────────────────
            progressNotifierPort.notify(jobId, "RAG Indexer", "RUNNING", null);
            codeIndexingPort.index(initial);
            progressNotifierPort.notify(jobId, "RAG Indexer", "DONE", null);

            // ── Phase 1–5: typed agent workflow ─────────────────────────────
            // Bug fix: previously this called session.startMigration() upfront
            // which left the session stuck in MIGRATING — that broke #10's
            // halt path because completePlan + requestApproval require
            // CONTEXT_ANALYSED / PLAN_READY.  Walk the state machine for real
            // now: PENDING → CONTEXT_ANALYSED → (post-workflow transitions
            // either to AWAITING_APPROVAL when halting, or MIGRATING → DONE
            // when running to completion).
            jobStatusUpdatePort.markAnalyzing(jobId);
            if (session.status() == com.altrix.orchestrator.domain.model.session.SessionStatus.PENDING) {
                session.beginContextAnalysis();
                session = sessionRepository.save(session);
            }

            MigrationState result = workflowExecution.execute(initial);

            // #10 — When workflow.require-approval.enabled is true the graph
            // halts at END right after the planner without producing an
            // ApprovedPlan.  Detect that here, persist the plan onto the
            // session, transition to AWAITING_APPROVAL, and exit cleanly so
            // the reviewer can take over via the approval REST endpoints.
            if (isHaltedForApproval(result)) {
                MigrationPlan plan = result.migrationPlan().orElseThrow();
                session.completePlan(plan);     // CONTEXT_ANALYSED → PLAN_READY
                session.requestApproval();      // PLAN_READY → AWAITING_APPROVAL
                sessionRepository.save(session);
                progressNotifierPort.notify(jobId, "Pipeline", "AWAITING_APPROVAL",
                        "Plan ready — awaiting human review.");
                log.info("Pipeline halted for approval — job '{}' session '{}'",
                        jobId, session.id());
                return initial;
            }

            List<MigratedFile> files = result.migrationArtifact()
                    .map(MigrationArtifact::files)
                    .orElse(List.of());

            if (!files.isEmpty()) {
                jobStatusUpdatePort.markMigrating(jobId);
            }

            String outputKey = migratedFileStoragePort.storeMigratedZip(jobId, files);
            cachePlanBestEffort(initial, files);

            // Walk the state machine for the auto-approval / no-halt path too:
            // we are at CONTEXT_ANALYSED, need to record the plan + reach DONE.
            result.migrationPlan().ifPresent(session::completePlan);   // → PLAN_READY
            if (session.status() == com.altrix.orchestrator.domain.model.session.SessionStatus.PLAN_READY) {
                session.startMigration();                              // → MIGRATING
            }
            session.resetAgentErrors();
            session.storeMigratedFiles(files);
            session.complete();                                        // → DONE
            sessionRepository.save(session);

            jobStatusUpdatePort.markDone(jobId, outputKey);
            progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                    "Migration complete. Ready to download.");
            log.info("Pipeline DONE for job '{}'", jobId);

            return files.isEmpty() ? initial : initial.withMigratedFiles(files);

        } catch (AiProviderUnavailableException e) {
            log.warn("All AI providers unavailable for job '{}' — attempting graceful degradation", jobId);
            Optional<List<MigratedFile>> cached = planCachePort.loadLatest(
                    initial.projectId(), targetStack(initial));

            if (cached.isPresent()) {
                log.info("Serving cached migration plan for job '{}' ({} file(s))",
                        jobId, cached.get().size());
                String outputKey = migratedFileStoragePort.storeMigratedZip(jobId, cached.get());
                session.complete();
                sessionRepository.save(session);
                jobStatusUpdatePort.markDone(jobId, outputKey);
                progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                        "Serving cached migration plan — AI providers are currently unavailable.");
                return initial.withMigratedFiles(cached.get());
            }

            log.error("Pipeline FAILED for job '{}' (no cached plan available): {}", jobId, e.getMessage());
            failSessionBestEffort(session, e.getMessage());
            jobStatusUpdatePort.markFailed(jobId, e.getMessage());
            progressNotifierPort.notify(jobId, "Pipeline", "FAILED",
                    "All AI providers unavailable. No cached plan found.");
            throw e;

        } catch (Exception e) {
            log.error("Pipeline FAILED for job '{}': {}", jobId, e.getMessage(), e);
            boolean autoPaused = applyAgentFailureBestEffort(session, e.getMessage());
            if (autoPaused) {
                log.warn("Session '{}' auto-paused after {} consecutive failures", session.id(), autoPauseThreshold);
                jobStatusUpdatePort.markFailed(jobId,
                        "Auto-paused after " + autoPauseThreshold + " consecutive failures — awaiting manual resume");
                progressNotifierPort.notify(jobId, "Pipeline", "PAUSED",
                        "Session auto-paused. Use POST /sessions/{id}/resume to retry.");
            } else {
                jobStatusUpdatePort.markFailed(jobId, e.getMessage());
                progressNotifierPort.notify(jobId, "Pipeline", "FAILED", e.getMessage());
                throw new AgentFailureException("Pipeline", e.getMessage());
            }
            return initial;
        }
    }

    private void failSessionBestEffort(WorkflowSession session, String reason) {
        try {
            session.fail(reason);
            sessionRepository.save(session);
        } catch (Exception ex) {
            log.warn("Could not persist FAILED state for session '{}' (non-fatal): {}",
                    session.id(), ex.getMessage());
        }
    }

    private boolean applyAgentFailureBestEffort(WorkflowSession session, String reason) {
        try {
            boolean paused = session.handleAgentFailure(reason, autoPauseThreshold);
            sessionRepository.save(session);
            return paused;
        } catch (Exception ex) {
            log.warn("Could not persist failure state for session '{}' (non-fatal): {}",
                    session.id(), ex.getMessage());
            return false;
        }
    }

    private void cachePlanBestEffort(ProjectContext initial, List<MigratedFile> files) {
        if (files == null || files.isEmpty()) return;
        try {
            planCachePort.store(initial.projectId(), targetStack(initial), files);
        } catch (Exception e) {
            log.warn("Failed to cache migration plan for project '{}' (non-fatal): {}",
                    initial.projectId(), e.getMessage());
        }
    }

    private static String targetStack(ProjectContext ctx) {
        if (ctx.detectionResult() != null && ctx.detectionResult().framework() != null) {
            return ctx.detectionResult().framework().name();
        }
        return "unknown";
    }

    /**
     * The graph produces a plan but no approved plan when it halts at the
     * approval gate (#10) — there's no artifact / validation either.
     */
    private static boolean isHaltedForApproval(MigrationState state) {
        return state.migrationPlan().isPresent()
                && state.approvedPlan().isEmpty()
                && state.migrationArtifact().isEmpty();
    }
}
