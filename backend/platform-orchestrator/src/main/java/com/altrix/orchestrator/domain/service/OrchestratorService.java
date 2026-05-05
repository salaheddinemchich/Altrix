package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.exception.AiProviderUnavailableException;
import com.altrix.orchestrator.domain.port.in.RunPipelineUseCase;
import com.altrix.orchestrator.domain.port.out.AgentPort;
import com.altrix.orchestrator.domain.port.out.CodeIndexingPort;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationPlanCachePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OrchestratorService implements RunPipelineUseCase {

    private final List<AgentPort>         agents;
    private final JobStatusUpdatePort     jobStatusUpdatePort;
    private final MigratedFileStoragePort migratedFileStoragePort;
    private final ProgressNotifierPort    progressNotifierPort;
    private final CodeIndexingPort        codeIndexingPort;
    private final MigrationPlanCachePort  planCachePort;
    private final long                    interAgentDelayMs;

    public OrchestratorService(
            List<AgentPort>         agents,
            JobStatusUpdatePort     jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort    progressNotifierPort,
            CodeIndexingPort        codeIndexingPort,
            MigrationPlanCachePort  planCachePort,
            long                    interAgentDelayMs
    ) {
        this.agents                  = agents.stream()
                .sorted(Comparator.comparingInt(AgentPort::getOrder))
                .toList();
        this.jobStatusUpdatePort     = jobStatusUpdatePort;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.progressNotifierPort    = progressNotifierPort;
        this.codeIndexingPort        = codeIndexingPort;
        this.planCachePort           = planCachePort;
        this.interAgentDelayMs       = interAgentDelayMs;
        log.info("OrchestratorService initialized — {} agents, delay={}ms",
                this.agents.size(), interAgentDelayMs);
    }

    @Override
    public ProjectContext run(ProjectContext initial) {
        String jobId = initial.jobId();
        log.info("Starting pipeline for job '{}'", jobId);
        ProjectContext context = initial;

        try {
            // Phase 0 — index source files into vector store for RAG retrieval
            progressNotifierPort.notify(jobId, "RAG Indexer", "RUNNING", null);
            codeIndexingPort.index(context);
            progressNotifierPort.notify(jobId, "RAG Indexer", "DONE", null);

            for (int i = 0; i < agents.size(); i++) {
                context = runAgent(agents.get(i), context);
                if (i < agents.size() - 1) {
                    pauseBeforeNextAgent(jobId);
                }
            }

            // Success path — persist output and cache plan for graceful degradation
            List<MigratedFile> files = context.migratedFiles();
            String outputKey = migratedFileStoragePort.storeMigratedZip(jobId, files);
            cachePlanBestEffort(initial, files);

            jobStatusUpdatePort.markDone(jobId, outputKey);
            progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                    "Migration complete. Ready to download.");
            log.info("Pipeline DONE for job '{}'", jobId);
            return context;

        } catch (AiProviderUnavailableException e) {
            // Graceful degradation — all AI providers unavailable, try cached plan (#148)
            log.warn("All AI providers unavailable for job '{}' — attempting graceful degradation", jobId);
            Optional<List<MigratedFile>> cached = planCachePort.loadLatest(
                    initial.projectId(), targetStack(initial));
            if (cached.isPresent()) {
                log.info("Serving cached migration plan for job '{}' ({} file(s))",
                        jobId, cached.get().size());
                String outputKey = migratedFileStoragePort
                        .storeMigratedZip(jobId, cached.get());
                jobStatusUpdatePort.markDone(jobId, outputKey);
                progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                        "Serving cached migration plan — AI providers are currently unavailable.");
                return initial.withMigratedFiles(cached.get());
            }
            // No cache entry — propagate as a regular failure
            log.error("Pipeline FAILED for job '{}' (no cached plan available): {}", jobId, e.getMessage());
            jobStatusUpdatePort.markFailed(jobId, e.getMessage());
            progressNotifierPort.notify(jobId, "Pipeline", "FAILED",
                    "All AI providers unavailable. No cached plan found.");
            throw e;

        } catch (Exception e) {
            log.error("Pipeline FAILED for job '{}': {}", jobId, e.getMessage(), e);
            jobStatusUpdatePort.markFailed(jobId, e.getMessage());
            progressNotifierPort.notify(jobId, "Pipeline", "FAILED", e.getMessage());
            throw e;
        }
    }

    private ProjectContext runAgent(AgentPort agent, ProjectContext context) {
        String jobId = context.jobId();
        log.info("Job '{}' — agent [{}] {}", jobId, agent.getOrder(), agent.getName());
        progressNotifierPort.notify(jobId, agent.getName(), "RUNNING", null);

        if (agent.getOrder() == 1) jobStatusUpdatePort.markAnalyzing(jobId);
        if (agent.getOrder() == 3) jobStatusUpdatePort.markMigrating(jobId);

        try {
            ProjectContext result = agent.execute(context);
            progressNotifierPort.notify(jobId, agent.getName(), "DONE", null);
            return result;
        } catch (AiProviderUnavailableException e) {
            progressNotifierPort.notify(jobId, agent.getName(), "FAILED", e.getMessage());
            throw e; // propagate so the outer catch can attempt graceful degradation
        } catch (Exception e) {
            progressNotifierPort.notify(jobId, agent.getName(), "FAILED", e.getMessage());
            throw new AgentFailureException(agent.getName(), e.getMessage());
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

    /** Extract a stable cache-key component from the detected framework. */
    private static String targetStack(ProjectContext ctx) {
        if (ctx.detectionResult() != null && ctx.detectionResult().framework() != null) {
            return ctx.detectionResult().framework().name();
        }
        return "unknown";
    }

    /**
     * Single deliberate pause between agents to respect AI provider rate limits.
     * Uses TimeUnit.MILLISECONDS.sleep() — not a busy-wait loop.
     */
    private void pauseBeforeNextAgent(String jobId) {
        if (interAgentDelayMs <= 0) return;
        log.info("Job '{}' — pausing {}ms before next agent (rate-limit guard)", jobId, interAgentDelayMs);
        try {
            TimeUnit.MILLISECONDS.sleep(interAgentDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Job '{}' — inter-agent pause interrupted", jobId);
            throw new RuntimeException("Pipeline interrupted during agent pause", e);
        }
    }
}
