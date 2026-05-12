package com.altrix.project.adapter.out.persistence;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.project.domain.model.ProjectStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * JPA persistence entity for the projects table.
 *
 * <p>This class lives in the adapter layer — never in the domain.
 * The domain model {@link com.altrix.project.domain.model.Project}
 * has zero JPA annotations. This entity is mapped to/from the domain
 * model by {@link ProjectMapper}.
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectJpaEntity {

    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_system", length = 20)
    private BuildSystem buildSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_format", length = 20)
    private ConfigFormat configFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "framework", length = 20)
    private DetectedFramework framework;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_format_preference", nullable = false, length = 20)
    private ConfigFormatPreference configFormatPreference;

    @Column(name = "eligible_for_migration", nullable = false)
    private boolean eligibleForMigration;

    /** Comma-separated list of detected technologies, e.g. "SPRING_BOOT,GRADLE_KOTLIN,GCP_PUBSUB". */
    @Column(name = "detected_technologies", columnDefinition = "TEXT")
    private String detectedTechnologies;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
