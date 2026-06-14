package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
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
    private final RagIndexManifestRepository ragIndexManifestRepository;
    private final int autoPauseThreshold;
    /**
     * Project Mapper (#blueprint) — builds the project-wide semantic map in
     * Phase 0.  Nullable so the pipeline degrades gracefully when the
     * blueprint subsystem is absent (e.g. in unit tests); a null mapper just
     * means agents fall back to file-by-file migration.
     */
    private final MigrationAgent<ProjectContext, ProjectBlueprint> projectMapper;

    public OrchestratorService(
            WorkflowExecutionPort workflowExecution,
            JobStatusUpdatePort jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort progressNotifierPort,
            CodeIndexingPort codeIndexingPort,
            MigrationPlanCachePort planCachePort,
            WorkflowSessionRepository sessionRepository,
            RagIndexManifestRepository ragIndexManifestRepository,
            int autoPauseThreshold,
            MigrationAgent<ProjectContext, ProjectBlueprint> projectMapper
    ) {
        this.workflowExecution = workflowExecution;
        this.jobStatusUpdatePort = jobStatusUpdatePort;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.progressNotifierPort = progressNotifierPort;
        this.codeIndexingPort = codeIndexingPort;
        this.planCachePort = planCachePort;
        this.sessionRepository = sessionRepository;
        this.ragIndexManifestRepository = ragIndexManifestRepository;
        this.autoPauseThreshold = autoPauseThreshold;
        this.projectMapper = projectMapper;
        log.info("OrchestratorService initialised — typed LangGraph4j workflow (auto-pause threshold={}, projectMapper={})",
                autoPauseThreshold, projectMapper != null ? "enabled" : "disabled");
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

        // Make the session id visible to everything that runs on this thread
        // for the rest of the pipeline: the Project Mapper (to persist the
        // blueprint), the inline workflow migrator (to LOAD the blueprint +
        // persist RAG provenance), and the Docker sandbox runners (to persist
        // logs).  Same ThreadLocal the resume path uses.  Cleared in the
        // finally below.  (The per-file migrator workers run on a pool that
        // can't see this ThreadLocal — the migrator resolves the blueprint
        // once on this thread and passes it down, see CoreMigratorAgent.)
        SandboxContext.setSessionId(session.id().value().toString());
        try {
            // ── Phase 0: RAG indexing ────────────────────────────────────────
            // CodeIndexingAgent emits granular progress events
            // (reading → chunking → embedding → done) AND returns a manifest
            // listing exactly which files made it into the vector store.
            // Persist the manifest so the JobDetail UI can show the reviewer
            // which resources were considered.  Best-effort: a DB hiccup here
            // doesn't roll back the embedding work.
            var manifest = codeIndexingPort.index(initial);
            try {
                ragIndexManifestRepository.save(session.id(), manifest);
            } catch (Exception persistErr) {
                log.warn("Could not persist RAG manifest for session '{}' (non-fatal): {}",
                        session.id(), persistErr.getMessage());
            }

            // ── Phase 0b: Project Mapper (semantic blueprint) ────────────────
            // Build the project-wide semantic map (classes / calls / inheritance
            // / features → Kafka targets) and persist it under this session so
            // the Core Migrator can enrich each per-file prompt with real
            // project understanding instead of migrating blind.  Best-effort:
            // any failure here must NOT abort the migration — agents fall back
            // to file-by-file behaviour when the blueprint is absent.
            if (projectMapper != null) {
                try {
                    var blueprint = projectMapper.execute(initial);
                    log.info("Project Mapper produced blueprint for session '{}' — {} class(es), {} file(s)",
                            session.id(),
                            blueprint != null && blueprint.semanticGraph() != null
                                    ? blueprint.semanticGraph().classes().size() : 0,
                            blueprint != null ? blueprint.files().size() : 0);
                } catch (Exception mapErr) {
                    log.warn("Project Mapper failed for session '{}' (non-fatal, falling back to "
                            + "file-by-file migration): {}", session.id(), mapErr.getMessage());
                }
            }

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

            // Re-fetch the session here so we hold the latest @Version before
            // applying the post-execute transitions.  Without this, anything
            // that bumped the row during workflow.execute() (event listeners,
            // schedulers, retries) leaves our in-memory copy stale and the
            // next save throws StaleObjectStateException — the
            // pre-existing optimistic-lock collision noted in HANDOFF.md.
            session = sessionRepository.findById(session.id()).orElse(session);

            // #10 — When workflow.require-approval.enabled is true the graph
            // halts at END right after the planner without producing an
            // ApprovedPlan.  Detect that here, persist the plan onto the
            // session, transition to AWAITING_APPROVAL, and exit cleanly so
            // the reviewer can take over via the approval REST endpoints.
            if (isHaltedForApproval(result)) {
                MigrationPlan plan = result.migrationPlan().orElseThrow();
                if (session.status() == com.altrix.orchestrator.domain.model.session.SessionStatus.CONTEXT_ANALYSED) {
                    session.completePlan(plan);     // CONTEXT_ANALYSED → PLAN_READY
                }
                if (session.status() == com.altrix.orchestrator.domain.model.session.SessionStatus.PLAN_READY) {
                    session.requestApproval();      // PLAN_READY → AWAITING_APPROVAL
                }
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
            // Each transition is guarded so a retry / re-fetched state that has
            // already advanced doesn't try to repeat itself.
            var contextAnalysed = com.altrix.orchestrator.domain.model.session.SessionStatus.CONTEXT_ANALYSED;
            var planReady       = com.altrix.orchestrator.domain.model.session.SessionStatus.PLAN_READY;
            var migrating       = com.altrix.orchestrator.domain.model.session.SessionStatus.MIGRATING;

            if (session.status() == contextAnalysed) {
                result.migrationPlan().ifPresent(session::completePlan);  // → PLAN_READY
            }
            if (session.status() == planReady) {
                session.startMigration();                                 // → MIGRATING
            }
            session.resetAgentErrors();
            session.storeMigratedFiles(files);
            if (session.status() == migrating) {
                session.complete();                                       // → DONE
            }
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
        } finally {
            // Always release the thread-local session id so it can't leak into
            // a pooled thread's next task.
            SandboxContext.clear();
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
