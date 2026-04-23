package com.migrator.job.adapter.out.persistence;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.common.domain.enums.JobStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "migration_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MigrationJobJpaEntity {

    @Id @Column(nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Storage key of the uploaded project ZIP in MinIO */
    @Column(name = "project_storage_key")
    private String projectStorageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_format_preference", nullable = false, length = 20)
    private ConfigFormatPreference configFormatPreference;

    @Column(name = "output_storage_key")
    private String outputStorageKey;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
