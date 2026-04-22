package com.migrator.job.domain.model;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.common.domain.enums.JobStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DDD Entity — represents one migration job in the system.
 *
 * <p>A job is created automatically when a project is registered.
 * It tracks the full lifecycle from PENDING to DONE or FAILED.
 *
 * <p>No JPA annotations here. The persistence adapter maps this
 * to {@link com.migrator.job.adapter.out.persistence.MigrationJobJpaEntity}.
 *
 * <p>State machine:
 * <pre>
 *   PENDING → ANALYZING → MIGRATING → DONE
 *   any state → FAILED
 *   any state → CANCELLED
 * </pre>
 */
@Getter
@Builder(toBuilder = true)
@With
public final class MigrationJob {

    private final String                 id;
    private final String                 projectId;
    private final String                 userId;
    private final JobStatus              status;
    private final ConfigFormatPreference configFormatPreference;

    /** MinIO key for the migrated output ZIP — set when DONE. */
    private final String  outputStorageKey;

    /** Human-readable error message — set when FAILED. */
    private final String  errorMessage;

    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant completedAt;

    // ── Factory ──────────────────────────────────────────────────────────────

    public static MigrationJob create(
            String projectId,
            String userId,
            ConfigFormatPreference configFormatPreference
    ) {
        Instant now = Instant.now();
        return MigrationJob.builder()
                .id(UUID.randomUUID().toString())
                .projectId(projectId)
                .userId(userId)
                .status(JobStatus.PENDING)
                .configFormatPreference(
                        Objects.requireNonNullElse(
                                configFormatPreference,
                                ConfigFormatPreference.KEEP_ORIGINAL))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ── State transitions — each returns a new immutable instance ────────────

    public MigrationJob startAnalyzing() {
        assertNotTerminal();
        return this.withStatus(JobStatus.ANALYZING)
                   .withUpdatedAt(Instant.now());
    }

    public MigrationJob startMigrating() {
        assertNotTerminal();
        return this.withStatus(JobStatus.MIGRATING)
                   .withUpdatedAt(Instant.now());
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

    // ── Guards ────────────────────────────────────────────────────────────────

    private void assertNotTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot transition job '" + id + "' — already in terminal state: " + status);
        }
    }

    // ── DDD Entity equality — by identity only ────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MigrationJob other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
