package com.migrator.project.adapter.messaging;

import com.migrator.project.domain.model.Project;
import com.migrator.project.domain.port.out.ProjectEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Secondary adapter — implements {@link ProjectEventPublisher} using Kafka.
 *
 * <p>Publishes a {@code project.registered} event carrying the project ID
 * and user ID so downstream services (platform-job) can react.
 *
 * <p>Message key   = projectId  (ensures ordering per project)
 * Message value  = projectId  (job service reads this to create a job)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProjectEventAdapter implements ProjectEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.project-registered}")
    private String topic;

    @Override
    public void publishProjectRegistered(Project project) {
        log.info("Publishing project.registered event for project '{}'", project.getId());

        kafkaTemplate.send(topic, project.getId(), project.getId())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish project.registered for '{}': {}",
                                project.getId(), ex.getMessage());
                    } else {
                        log.debug("project.registered published to partition {} offset {}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
