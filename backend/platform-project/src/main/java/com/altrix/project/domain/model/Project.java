package com.altrix.project.domain.model;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.DetectedFramework;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * DDD Entity representing an uploaded project awaiting or undergoing migration.
 *
 * <p>This is the domain model — it has NO JPA annotations. The persistence
 * adapter maps this to {@code ProjectJpaEntity} before saving to the database.
 *
 * <p>Identity is defined by {@code id}, not by field values.
 */
@Getter
@Builder(toBuilder = true)
@With
public final class Project {

    /**
     * Unique identifier — assigned at creation, never changes.
     */
    private final String id;

    /**
     * The user who uploaded this project.
     */
    private final String userId;

    /**
     * Human-readable project name, derived from the uploaded filename.
     */
    private final String name;

    /**
     * MinIO object key where the original uploaded ZIP is stored.
     */
    private final String storageKey;

    /**
     * Current lifecycle status of this project.
     */
    private final ProjectStatus status;

    // ── Populated after detection phase ───────────────────────────────────

    private final BuildSystem buildSystem;
    private final ConfigFormat configFormat;
    private final DetectedFramework framework;

    /**
     * User's preference for the output config format. Defaults to KEEP_ORIGINAL.
     */
    private final ConfigFormatPreference configFormatPreference;

    /**
     * True when the project uses GCP Pub/Sub and is eligible for migration.
     */
    private final boolean eligibleForMigration;

    /**
     * Technologies detected during analysis (e.g. GCP_PUBSUB, SPRING_BOOT).
     */
    private final List<String> detectedTechnologies;

    // ── Issue #90 — git-ingestion provenance ──────────────────────────────

    /**
     * HTTPS clone URL when the project was ingested via git, else {@code null}.
     */
    private final String repoUrl;

    /**
     * Branch / tag checked out at clone time, else {@code null}.
     */
    private final String trackedBranch;

    /**
     * Where this project row came from; {@code null} on rows created before #90.
     */
    private final ProjectSource source;

    private final Instant createdAt;
    private final Instant updatedAt;

    /**
     * Factory method — creates a brand-new project in PENDING status.
     * Assigns a new UUID and sets timestamps to now.
     *
     * <p>Used by the legacy ZIP-upload path; produces a row with no git
     * provenance ({@code source = MANUAL}).
     */
    public static Project create(
            String userId,
            String name,
            String storageKey,
            ConfigFormatPreference configFormatPreference
    ) {
        Instant now = Instant.now();
        return Project.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .name(name)
                .storageKey(storageKey)
                .status(ProjectStatus.PENDING)
                .configFormatPreference(
                        Objects.requireNonNullElse(configFormatPreference,
                                ConfigFormatPreference.KEEP_ORIGINAL))
                .source(ProjectSource.MANUAL)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Factory for projects ingested via git clone (#90).
     *
     * <p>Stores {@code repoUrl}, {@code trackedBranch}, and {@code source} so
     * webhook consumers can locate this project when a push arrives.
     */
    public static Project createFromGit(
            String userId,
            String name,
            String storageKey,
            ConfigFormatPreference configFormatPreference,
            String repoUrl,
            String trackedBranch,
            ProjectSource source
    ) {
        Instant now = Instant.now();
        return Project.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .name(name)
                .storageKey(storageKey)
                .status(ProjectStatus.PENDING)
                .configFormatPreference(
                        Objects.requireNonNullElse(configFormatPreference,
                                ConfigFormatPreference.KEEP_ORIGINAL))
                .repoUrl(Objects.requireNonNull(repoUrl, "repoUrl"))
                .trackedBranch(trackedBranch)
                .source(Objects.requireNonNullElse(source, ProjectSource.GIT_CLONE))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Returns a new Project with detection results applied and status set to READY.
     * Follows immutability — does not mutate this instance.
     */
    public Project withDetectionApplied(
            BuildSystem buildSystem,
            ConfigFormat configFormat,
            DetectedFramework framework,
            boolean eligibleForMigration,
            List<String> detectedTechnologies
    ) {
        return this.toBuilder()
                .buildSystem(buildSystem)
                .configFormat(configFormat)
                .framework(framework)
                .eligibleForMigration(eligibleForMigration)
                .detectedTechnologies(detectedTechnologies)
                .status(ProjectStatus.READY)
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * Returns a new Project with status set to ERROR.
     */
    public Project withError() {
        return this.withStatus(ProjectStatus.ERROR)
                .withUpdatedAt(Instant.now());
    }

    // DDD Entity equality — identity only, never by field values
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Project other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
