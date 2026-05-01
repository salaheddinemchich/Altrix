package com.migrator.orchestrator.adapter.out.messaging;

import com.migrator.orchestrator.domain.port.out.JobStatusUpdatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Secondary adapter — publishes job status update events to Kafka.
 * platform-job consumes these to update job state in its own DB.
 *
 * <p>Topic: migration.job.status.update
 * Key:   jobId
 * Value: STATUS[:outputKey] or STATUS:reason
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaJobStatusAdapter implements JobStatusUpdatePort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.job-status-update}")
    private String topic;

    @Override
    public void markAnalyzing(String jobId) {
        send(jobId, "ANALYZING");
    }

    @Override
    public void markMigrating(String jobId) {
        send(jobId, "MIGRATING");
    }

    @Override
    public void markDone(String jobId, String outputStorageKey) {
        send(jobId, "DONE:" + outputStorageKey);
    }

    @Override
    public void markFailed(String jobId, String reason) {
        send(jobId, "FAILED:" + reason);
    }

    private void send(String jobId, String value) {
        kafkaTemplate.send(topic, jobId, value)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish status update for job '{}': {}",
                                jobId, ex.getMessage());
                    } else {
                        log.debug("Status update sent for job '{}': {}", jobId, value);
                    }
                });
    }
}
