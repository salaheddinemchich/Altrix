package com.altrix.job.adapter.out.messaging;

import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.out.JobEventPublisherPort;
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
 *   value = projectId|storageKey|jakartaMessagingTarget
 *
 * The 3rd segment is additive — consumers built against the old 2-part
 * format (and any in-flight messages from before this change) still parse;
 * see {@code JobCreatedListener} for the backward-compatible split.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaJobEventAdapter implements JobEventPublisherPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.job-created}")
    private String jobCreatedTopic;

    @Value("${kafka.topics.job-completed}")
    private String jobCompletedTopic;

    @Override
    public void publishJobCreated(MigrationJob job) {
        // value = "projectId|storageKey|jakartaMessagingTarget"
        String value = job.getProjectId() + "|" + job.getProjectStorageKey()
                + "|" + job.getJakartaMessagingTarget().name();
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
