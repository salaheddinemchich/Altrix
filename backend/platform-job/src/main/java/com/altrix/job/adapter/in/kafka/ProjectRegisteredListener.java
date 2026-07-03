package com.altrix.job.adapter.in.kafka;

import com.altrix.common.domain.enums.JakartaMessagingTarget;
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
        // value = "userId|storageKey|jakartaMessagingTarget" — limit 3 so a
        // literal "|" inside storageKey (if one ever sneaks in) doesn't get
        // misrouted into the target segment. The 3rd segment is additive —
        // legacy 2-part messages (in-flight during a rolling deploy) still
        // parse, just without a 3rd segment, defaulting below.
        String[] parts = value.split("\\|", 3);
        String userId = parts[0];
        String storageKey = parts.length > 1 ? parts[1] : "";
        JakartaMessagingTarget jakartaMessagingTarget = parseTarget(parts.length > 2 ? parts[2] : null);
        log.info("Creating job for project '{}' user '{}' storageKey='{}' jakartaMessagingTarget={}",
                projectId, userId, storageKey, jakartaMessagingTarget);
        createJobUseCase.createJob(projectId, userId, storageKey, null, null, jakartaMessagingTarget);
    }

    private static JakartaMessagingTarget parseTarget(String raw) {
        if (raw == null || raw.isBlank()) return JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS;
        try {
            return JakartaMessagingTarget.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS;
        }
    }
}
