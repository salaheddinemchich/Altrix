package com.migrator.orchestrator.domain.service;

import com.migrator.common.domain.model.ProjectContext;
import com.migrator.common.exception.AgentFailureException;
import com.migrator.orchestrator.domain.port.in.RunPipelineUseCase;
import com.migrator.orchestrator.domain.port.out.AgentPort;
import com.migrator.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.migrator.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.migrator.orchestrator.domain.port.out.ProgressNotifierPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;

/**
 * Core domain service — runs the agent pipeline.
 *
 * <p>Pure Java. No Spring, no Kafka, no MinIO, no WebSocket imported.
 *
 * <p>Pipeline execution:
 * <ol>
 *   <li>Sort agents by {@link AgentPort#getOrder()}</li>
 *   <li>Run each agent — pass current context in, get enriched context out</li>
 *   <li>Broadcast progress after each agent via {@link ProgressNotifierPort}</li>
 *   <li>Update job status in platform-job via {@link JobStatusUpdatePort}</li>
 *   <li>On completion — store migrated ZIP and mark job DONE</li>
 *   <li>On any failure — mark job FAILED and rethrow</li>
 * </ol>
 */
@Slf4j
public class OrchestratorService implements RunPipelineUseCase {

    private final List<AgentPort>         agents;
    private final JobStatusUpdatePort     jobStatusUpdatePort;
    private final MigratedFileStoragePort migratedFileStoragePort;
    private final ProgressNotifierPort    progressNotifierPort;

    public OrchestratorService(
            List<AgentPort>         agents,
            JobStatusUpdatePort     jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort    progressNotifierPort
    ) {
        // Sort once at construction — agents run in fixed order
        this.agents                  = agents.stream()
                .sorted(Comparator.comparingInt(AgentPort::getOrder))
                .toList();
        this.jobStatusUpdatePort     = jobStatusUpdatePort;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.progressNotifierPort    = progressNotifierPort;
    }

    @Override
    public ProjectContext run(ProjectContext initial) {
        String jobId = initial.jobId();
        log.info("Starting pipeline for job '{}'", jobId);

        ProjectContext context = initial;

        try {
            for (AgentPort agent : agents) {
                context = runAgent(agent, context);
            }

            // All agents done — store output ZIP and mark DONE
            String outputKey = migratedFileStoragePort
                    .storeMigratedZip(jobId, context.migratedFiles());

            jobStatusUpdatePort.markDone(jobId, outputKey);
            progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                    "Migration complete. Output ready for download.");

            log.info("Pipeline DONE for job '{}'", jobId);
            return context;

        } catch (Exception e) {
            log.error("Pipeline FAILED for job '{}': {}", jobId, e.getMessage(), e);
            jobStatusUpdatePort.markFailed(jobId, e.getMessage());
            progressNotifierPort.notify(jobId, "Pipeline", "FAILED", e.getMessage());
            throw e;
        }
    }

    private ProjectContext runAgent(AgentPort agent, ProjectContext context) {
        String jobId = context.jobId();
        log.info("Job '{}' — running agent [{}] {}", jobId, agent.getOrder(), agent.getName());

        progressNotifierPort.notify(jobId, agent.getName(), "RUNNING", null);

        // Advance job status for known agents
        if (agent.getOrder() == 1) jobStatusUpdatePort.markAnalyzing(jobId);
        if (agent.getOrder() == 3) jobStatusUpdatePort.markMigrating(jobId);

        try {
            ProjectContext result = agent.execute(context);
            progressNotifierPort.notify(jobId, agent.getName(), "DONE", null);
            log.info("Job '{}' — agent {} DONE", jobId, agent.getName());
            return result;

        } catch (Exception e) {
            String reason = "Agent '" + agent.getName() + "' failed: " + e.getMessage();
            progressNotifierPort.notify(jobId, agent.getName(), "FAILED", reason);
            throw new AgentFailureException(agent.getName(), e.getMessage());
        }
    }
}
