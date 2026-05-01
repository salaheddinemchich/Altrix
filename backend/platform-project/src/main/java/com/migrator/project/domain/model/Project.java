package com.migrator.project.domain.model;

import com.migrator.common.domain.enums.BuildSystem;
import com.migrator.common.domain.enums.ConfigFormat;
import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.common.domain.enums.DetectedFramework;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
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

    /** Unique identifier — assigned at creation, never changes. */
    private final String id;

    /** The user who uploaded this project. */
    private final String userId;

    /** Human-readable project name, derived from the uploaded filename. */
    private final String name;

    /** MinIO object key where the original uploaded ZIP is stored. */
    private final String storageKey;

    /** Current lifecycle status of this project. */
    private final ProjectStatus status;

    // ── Populated after detection phase ───────────────────────────────────

    private final BuildSystem buildSystem;
    private final ConfigFormat configFormat;
    private final DetectedFramework framework;

    /** User's preference for the output config format. Defaults to KEEP_ORIGINAL. */
    private final ConfigFormatPreference configFormatPreference;

    private final Instant createdAt;
    private final Instant updatedAt;

    /**
     * Factory method — creates a brand-new project in PENDING status.
     * Assigns a new UUID and sets timestamps to now.
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
            DetectedFramework framework
    ) {
        return this.toBuilder()
                .buildSystem(buildSystem)
                .configFormat(configFormat)
                .framework(framework)
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
