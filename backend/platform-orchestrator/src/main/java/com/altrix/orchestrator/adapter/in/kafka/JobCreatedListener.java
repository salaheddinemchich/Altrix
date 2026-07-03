package com.altrix.orchestrator.adapter.in.kafka;

import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.port.in.RunPipelineUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes migration.job.created events.
 * <p>
 * Message format:
 * key   = jobId
 * value = projectId|storageKey|jakartaMessagingTarget
 * <p>
 * The 3rd segment is additive — legacy 2-part messages (in-flight during a
 * rolling deploy) still parse, just without a 3rd segment, defaulting below.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobCreatedListener {

    private final RunPipelineUseCase runPipelineUseCase;

    @KafkaListener(
            topics = "${kafka.topics.job-created}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onJobCreated(ConsumerRecord<String, String> record) {
        String jobId = record.key();
        String value = record.value();
        if (jobId == null || value == null) return;
        // value = "projectId|storageKey|jakartaMessagingTarget" — limit 3 so a
        // literal "|" inside storageKey (if one ever sneaks in) doesn't get
        // misrouted into the target segment.
        String[] parts = value.split("\\|", 3);
        String projectId = parts.length > 0 ? parts[0] : "";
        String storageKey = parts.length > 1 ? parts[1] : "";
        JakartaMessagingTarget jakartaMessagingTarget = parseTarget(parts.length > 2 ? parts[2] : null);
        log.info("Received job.created — jobId='{}' projectId='{}' storageKey='{}' jakartaMessagingTarget={}",
                jobId, projectId, storageKey, jakartaMessagingTarget);
        ProjectContext initial = ProjectContext
                .builder()
                .jobId(jobId)
                .projectId(projectId)
                .storageKey(storageKey)
                .jakartaMessagingTarget(jakartaMessagingTarget)
                .build();
        try {
            runPipelineUseCase.run(initial);
        } catch (Exception e) {
            // Already handled inside OrchestratorService — don't rethrow
            // Rethrowing causes Kafka to retry the same failed job endlessly
            log.error("Pipeline failed for job '{}' — not retrying: {}", jobId, e.getMessage());
        }
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
