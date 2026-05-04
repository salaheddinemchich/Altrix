package com.altrix.job.domain.model;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JobStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Builder(toBuilder = true)
@With
public final class MigrationJob {

    private final String id;
    private final String projectId;
    private final String userId;

    /** MinIO storage key of the uploaded project ZIP — needed by orchestrator. */
    private final String projectStorageKey;

    private final JobStatus status;
    private final ConfigFormatPreference configFormatPreference;
    private final String outputStorageKey;
    private final String errorMessage;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant completedAt;

    public static MigrationJob create(
            String projectId,
            String userId,
            String projectStorageKey,
            ConfigFormatPreference configFormatPreference
    ) {
        Instant now = Instant.now();
        return MigrationJob.builder()
                .id(UUID.randomUUID().toString())
                .projectId(projectId)
                .userId(userId)
                .projectStorageKey(projectStorageKey)
                .status(JobStatus.PENDING)
                .configFormatPreference(
                        Objects.requireNonNullElse(
                                configFormatPreference,
                                ConfigFormatPreference.KEEP_ORIGINAL))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public MigrationJob startAnalyzing() {
        assertNotTerminal();
        return this.withStatus(JobStatus.ANALYZING).withUpdatedAt(Instant.now());
    }

    public MigrationJob startMigrating() {
        assertNotTerminal();
        return this.withStatus(JobStatus.MIGRATING).withUpdatedAt(Instant.now());
    }

    public MigrationJob complete(String outputStorageKey) {
        assertNotTerminal();
        Instant now = Instant.now();
        return this.withStatus(JobStatus.DONE)
                   .withOutputStorageKey(outputStorageKey)
                   .withUpdatedAt(now)
                   .withCompletedAt(now);
    }

    public MigrationJob fail(String reason) {
        Instant now = Instant.now();
        return this.withStatus(JobStatus.FAILED)
                   .withErrorMessage(reason)
                   .withUpdatedAt(now)
                   .withCompletedAt(now);
    }

    public MigrationJob cancel() {
        assertNotTerminal();
        Instant now = Instant.now();
        return this.withStatus(JobStatus.CANCELLED)
                   .withUpdatedAt(now)
                   .withCompletedAt(now);
    }


    /** Returns true if the job is in a terminal state and cannot transition further. */
    public boolean isTerminalStatus() {
        return status.isTerminal();
    }

    private void assertNotTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot transition job '" + id + "' — already in terminal state: " + status);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MigrationJob other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
// Note: this line won't work as append — use str_replace below
