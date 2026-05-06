package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.model.workflow.MigrationState;
import com.altrix.orchestrator.domain.port.in.RunPipelineUseCase;
import com.altrix.orchestrator.domain.port.out.CodeIndexingPort;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationPlanCachePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.port.out.WorkflowExecutionPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
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

    private final WorkflowExecutionPort     workflowExecution;
    private final JobStatusUpdatePort       jobStatusUpdatePort;
    private final MigratedFileStoragePort   migratedFileStoragePort;
    private final ProgressNotifierPort      progressNotifierPort;
    private final CodeIndexingPort          codeIndexingPort;
    private final MigrationPlanCachePort    planCachePort;
    private final WorkflowSessionRepository sessionRepository;

    public OrchestratorService(
            WorkflowExecutionPort     workflowExecution,
            JobStatusUpdatePort       jobStatusUpdatePort,
            MigratedFileStoragePort   migratedFileStoragePort,
            ProgressNotifierPort      progressNotifierPort,
            CodeIndexingPort          codeIndexingPort,
            MigrationPlanCachePort    planCachePort,
            WorkflowSessionRepository sessionRepository
    ) {
        this.workflowExecution    = workflowExecution;
        this.jobStatusUpdatePort  = jobStatusUpdatePort;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.progressNotifierPort = progressNotifierPort;
        this.codeIndexingPort     = codeIndexingPort;
        this.planCachePort        = planCachePort;
        this.sessionRepository    = sessionRepository;
        log.info("OrchestratorService initialised — typed LangGraph4j workflow");
    }

    @Override
    public ProjectContext run(ProjectContext initial) {
        String jobId = initial.jobId();
        log.info("Starting pipeline for job '{}'", jobId);

        WorkflowSession session = sessionRepository.save(
                WorkflowSession.create(jobId, initial.projectId()));

        try {
            // ── Phase 0: RAG indexing ────────────────────────────────────────
            progressNotifierPort.notify(jobId, "RAG Indexer", "RUNNING", null);
            codeIndexingPort.index(initial);
            progressNotifierPort.notify(jobId, "RAG Indexer", "DONE", null);

            // ── Phase 1–5: typed agent workflow ─────────────────────────────
            jobStatusUpdatePort.markAnalyzing(jobId);
            session.startMigration();                           // PENDING → MIGRATING
            session = sessionRepository.save(session);

            MigrationState result = workflowExecution.execute(initial);

            List<MigratedFile> files = result.migrationArtifact()
                    .map(MigrationArtifact::files)
                    .orElse(List.of());

            if (!files.isEmpty()) {
                jobStatusUpdatePort.markMigrating(jobId);
            }

            String outputKey = migratedFileStoragePort.storeMigratedZip(jobId, files);
            cachePlanBestEffort(initial, files);

            session.complete();                                 // MIGRATING → DONE
            sessionRepository.save(session);

            jobStatusUpdatePort.markDone(jobId, outputKey);
            progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                    "Migration complete. Ready to download.");
            log.info("Pipeline DONE for job '{}'", jobId);

            return files.isEmpty() ? initial : initial.withMigratedFiles(files);

        } catch (AiProviderUnavailableException e) {
            // Graceful degradation — all providers unavailable; try cached plan
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
            failSessionBestEffort(session, e.getMessage());
            jobStatusUpdatePort.markFailed(jobId, e.getMessage());
            progressNotifierPort.notify(jobId, "Pipeline", "FAILED", e.getMessage());
            throw new AgentFailureException("Pipeline", e.getMessage());
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void failSessionBestEffort(WorkflowSession session, String reason) {
        try {
            session.fail(reason);
            sessionRepository.save(session);
        } catch (Exception ex) {
            log.warn("Could not persist FAILED state for session '{}' (non-fatal): {}",
                    session.id(), ex.getMessage());
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
}
