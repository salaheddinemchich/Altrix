package com.migrator.job.adapter.in.kafka;

import com.migrator.job.domain.port.in.CreateJobUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Primary adapter — Kafka listener for project.registered events.
 *
 * <p>When platform-project publishes a project.registered event,
 * this listener creates a migration job automatically.
 *
 * <p>Message key   = projectId
 * Message value  = projectId
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRegisteredListener {

    private final CreateJobUseCase createJobUseCase;

    @KafkaListener(
            topics   = "${kafka.topics.project-registered}",
            groupId  = "${spring.kafka.consumer.group-id}"
    )
    public void onProjectRegistered(ConsumerRecord<String, String> record) {
        String projectId = record.key();
        String userId    = record.value(); // platform-project sends userId as value

        log.info("Received project.registered for project '{}' user '{}'",
                projectId, userId);

        createJobUseCase.createJob(projectId, userId, null);
    }
}
