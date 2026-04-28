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

@Slf4j
public class OrchestratorService implements RunPipelineUseCase {

    private final List<AgentPort>         agents;
    private final JobStatusUpdatePort     jobStatusUpdatePort;
    private final MigratedFileStoragePort migratedFileStoragePort;
    private final ProgressNotifierPort    progressNotifierPort;
    private final long                    interAgentDelayMs;

    public OrchestratorService(
            List<AgentPort>         agents,
            JobStatusUpdatePort     jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort    progressNotifierPort,
            long                    interAgentDelayMs
    ) {
        this.agents               = agents.stream()
                .sorted(Comparator.comparingInt(AgentPort::getOrder))
                .toList();
        this.jobStatusUpdatePort  = jobStatusUpdatePort;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.progressNotifierPort = progressNotifierPort;
        this.interAgentDelayMs    = interAgentDelayMs;
        log.info("OrchestratorService initialized — {} agents, delay={}ms",
                this.agents.size(), interAgentDelayMs);
    }

    @Override
    public ProjectContext run(ProjectContext initial) {
        String jobId = initial.jobId();
        log.info("Starting pipeline for job '{}'", jobId);
        ProjectContext context = initial;

        try {
            for (int i = 0; i < agents.size(); i++) {
                context = runAgent(agents.get(i), context);

                if (i < agents.size() - 1 && interAgentDelayMs > 0) {
                    log.info("Waiting {}ms between agents (rate limit guard)...", interAgentDelayMs);
                    Thread.sleep(interAgentDelayMs);
                }
            }

            String outputKey = migratedFileStoragePort
                    .storeMigratedZip(jobId, context.migratedFiles());

            jobStatusUpdatePort.markDone(jobId, outputKey);
            progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                    "Migration complete. Ready to download.");
            log.info("Pipeline DONE for job '{}'", jobId);
            return context;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobStatusUpdatePort.markFailed(jobId, "Pipeline interrupted");
            throw new RuntimeException("Pipeline interrupted", e);
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
        } catch (Exception e) {
            progressNotifierPort.notify(jobId, agent.getName(), "FAILED", e.getMessage());
            throw new AgentFailureException(agent.getName(), e.getMessage());
        }
    }
}
