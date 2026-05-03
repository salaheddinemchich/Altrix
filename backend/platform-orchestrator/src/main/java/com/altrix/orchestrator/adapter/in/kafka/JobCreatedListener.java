package com.altrix.orchestrator.adapter.in.kafka;

import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.port.in.RunPipelineUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes migration.job.created events.
 *
 * Message format:
 *   key   = jobId
 *   value = projectId|storageKey
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobCreatedListener {

    private final RunPipelineUseCase runPipelineUseCase;

    @KafkaListener(
            topics  = "${kafka.topics.job-created}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onJobCreated(ConsumerRecord<String, String> record) {
        String jobId = record.key();
        String value = record.value();

        if (jobId == null || value == null) return;

        // value = "projectId|storageKey"
        String[] parts      = value.split("\\|", 2);
        String projectId    = parts[0];
        String storageKey   = parts.length > 1 ? parts[1] : "";

        log.info("Received job.created — jobId='{}' projectId='{}' storageKey='{}'",
                jobId, projectId, storageKey);

        ProjectContext initial = ProjectContext.builder()
                .jobId(jobId)
                .projectId(projectId)
                .storageKey(storageKey)
                .build();

        try {
            runPipelineUseCase.run(initial);
        } catch (Exception e) {
            // Already handled inside OrchestratorService — don't rethrow
            // Rethrowing causes Kafka to retry the same failed job endlessly
            log.error("Pipeline failed for job '{}' — not retrying: {}", jobId, e.getMessage());
        }
    }
}
