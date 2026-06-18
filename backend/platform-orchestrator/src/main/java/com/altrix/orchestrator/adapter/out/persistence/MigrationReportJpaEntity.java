package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.ReportSummary;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code migration_reports} table.
 *
 * <p>Append-only history — re-running the migration for a session inserts
 * a new row with {@code version = max(version) + 1} (#162).  PK is the
 * synthetic {@code report_id}; {@code (session_id, version)} is uniquely
 * indexed to prevent duplicates within a session.
 */
@Entity
@Table(name = "migration_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationReportJpaEntity {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    /** Monotonic per-session version — 1 for the first row, increments on re-runs. */
    @Column(nullable = false, updatable = false)
    private Integer version;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** Structured companion to {@code content} — see {@link ReportSummary}. Nullable for pre-existing rows. */
    @Convert(converter = ReportSummaryJsonConverter.class)
    @Column(name = "structured_summary", columnDefinition = "jsonb")
    private ReportSummary structuredSummary;
}
