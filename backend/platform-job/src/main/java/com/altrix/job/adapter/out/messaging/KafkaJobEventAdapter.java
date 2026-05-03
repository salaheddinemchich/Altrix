package com.altrix.job.adapter.out.messaging;

import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.out.JobEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes job events.
 *
 * job.created message format:
 *   key   = jobId
 *   value = projectId|storageKey
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaJobEventAdapter implements JobEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.job-created}")
    private String jobCreatedTopic;

    @Value("${kafka.topics.job-completed}")
    private String jobCompletedTopic;

    @Override
    public void publishJobCreated(MigrationJob job) {
        // value = "projectId|storageKey" — orchestrator needs both
        String value = job.getProjectId() + "|" + job.getProjectStorageKey();
        kafkaTemplate.send(jobCreatedTopic, job.getId(), value)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish job.created for '{}': {}",
                                job.getId(), ex.getMessage());
                    } else {
                        log.info("job.created published for job '{}'", job.getId());
                    }
                });
    }

    @Override
    public void publishJobCompleted(MigrationJob job) {
        String payload = job.getId() + ":" + job.getStatus().name();
        kafkaTemplate.send(jobCompletedTopic, job.getId(), payload);
    }
}
