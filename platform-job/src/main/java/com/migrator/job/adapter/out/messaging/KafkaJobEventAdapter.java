package com.migrator.job.adapter.out.messaging;

import com.migrator.job.domain.model.MigrationJob;
import com.migrator.job.domain.port.out.JobEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Secondary adapter — publishes job events to Kafka.
 *
 * <p>Message key = jobId (ensures partition ordering per job).
 * Message value = projectId (orchestrator needs this to load files).
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
        kafkaTemplate.send(jobCreatedTopic, job.getId(), job.getProjectId())
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
        kafkaTemplate.send(jobCompletedTopic, job.getId(), payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish job.completed for '{}': {}",
                                job.getId(), ex.getMessage());
                    } else {
                        log.debug("job.completed published for job '{}'", job.getId());
                    }
                });
    }
}
