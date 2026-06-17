package com.altrix.project.adapter.out.messaging;

import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.port.out.ProjectEventPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes project.registered events.
 *
 * Message format:
 *   key   = projectId
 *   value = userId|storageKey
 *
 * Pipe-delimited so the job service can extract both userId and storageKey.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProjectEventAdapter implements ProjectEventPublisherPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.project-registered}")
    private String topic;

    @Override
    public void publishProjectRegistered(Project project) {
        // value = "userId|storageKey" — both needed by downstream services
        String value = project.getUserId() + "|" + project.getStorageKey();
        log.info("Publishing project.registered for project '{}' storageKey='{}'", project.getId(), project.getStorageKey());
        kafkaTemplate.send(topic, project.getId(), value)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish project.registered for '{}': {}",
                                project.getId(), ex.getMessage());
                    }
                });
    }
}
