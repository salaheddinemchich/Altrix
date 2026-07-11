package com.altrix.job.adapter.in.kafka;

import com.altrix.job.domain.port.in.UpdateJobStatusUseCase;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStatusUpdateListener {

    private final UpdateJobStatusUseCase updateJobStatusUseCase;
    private final JobRepositoryPort jobRepository;

    @KafkaListener(
            topics  = "${kafka.topics.job-status-update}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onStatusUpdate(ConsumerRecord<String, String> record) {
        String jobId = record.key();
        String value = record.value();

        if (jobId == null || value == null) return;

        // Skip if job is already in terminal state — avoids illegal state transitions
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus().isTerminal()) {
                log.debug("Job '{}' already terminal ({}), skipping status update '{}'", jobId, job.getStatus(), value);
                return;
            }

            log.info("Status update for job '{}': {}", jobId, value);

            try {
                if (value.startsWith("DONE:")) {
                    updateJobStatusUseCase.markDone(jobId, value.substring(5));
                } else if (value.startsWith("FAILED:")) {
                    updateJobStatusUseCase.markFailed(jobId, value.substring(7));
                } else if ("ANALYZING".equals(value)) {
                    updateJobStatusUseCase.markAnalyzing(jobId);
                } else if ("MIGRATING".equals(value)) {
                    updateJobStatusUseCase.markMigrating(jobId);
                }
            } catch (IllegalStateException e) {
                log.warn("Skipping invalid transition for job '{}': {}", jobId, e.getMessage());
            }
        });
    }
}
