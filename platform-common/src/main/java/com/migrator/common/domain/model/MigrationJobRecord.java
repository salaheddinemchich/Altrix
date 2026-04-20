package com.migrator.common.domain.model;

import com.migrator.common.domain.enums.JobStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class MigrationJobRecord {
    private UUID jobId;
    private String name;
    private String sourceTopic;
    private String targetTopic;
    private JobStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
