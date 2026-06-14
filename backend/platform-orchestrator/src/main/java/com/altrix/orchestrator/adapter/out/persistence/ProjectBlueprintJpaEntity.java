package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for {@code project_blueprints} (Flyway V23) — one row per
 * workflow session, JSONB-serialised {@link ProjectBlueprint} aggregate.
 *
 * <p>{@code schemaVersion} is duplicated as a column so admin scripts can
 * find blueprints that need migrating to a new aggregate shape without
 * having to deserialise every row.
 */
@Entity
@Table(name = "project_blueprints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectBlueprintJpaEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Convert(converter = ProjectBlueprintJsonConverter.class)
    @Column(name = "blueprint", nullable = false, columnDefinition = "jsonb")
    private ProjectBlueprint blueprint;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "schema_version", nullable = false)
    private short schemaVersion;
}
