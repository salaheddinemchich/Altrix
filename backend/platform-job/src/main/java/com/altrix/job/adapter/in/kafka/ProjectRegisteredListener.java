package com.altrix.job.adapter.in.kafka;

import com.altrix.job.domain.port.in.CreateJobUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRegisteredListener {

    private final CreateJobUseCase createJobUseCase;

    @KafkaListener(
            topics = "${kafka.topics.project-registered}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onProjectRegistered(ConsumerRecord<String, String> record) {
        String projectId = record.key();
        String value = record.value();
        if (projectId == null || value == null) return;
        // value = "userId|storageKey"
        String[] parts = value.split("\\|", 2);
        String userId = parts[0];
        String storageKey = parts.length > 1 ? parts[1] : "";
        log.info("Creating job for project '{}' user '{}' storageKey='{}'", projectId, userId, storageKey);
        createJobUseCase.createJob(projectId, userId, storageKey, null, null);
    }
}
