package com.migrator.common.domain.model;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import lombok.Builder;
import lombok.With;

import java.util.List;

/**
 * The central context object that flows through the entire agent pipeline.
 *
 * <p>This object starts nearly empty when a job is dispatched and grows
 * as each agent completes its work and returns an enriched copy.
 *
 * <p><strong>Immutability contract:</strong> agents never mutate this object.
 * Each agent receives the current context and returns a new instance via
 * the {@code @With} generated {@code withXxx()} methods (Lombok).
 *
 * <pre>
 * Initial context (jobId, projectId only)
 *    → Agent 1 returns context.withDetectionResult(...).withPubSubTopics(...)
 *    → Agent 3 returns context.withMigratedFiles(...)
 * </pre>
 *
 * <p>This is a DDD Aggregate root for the pipeline execution — it carries
 * all domain state needed to produce the final migrated output.
 */
@Builder
@With
public record ProjectContext(

        /** Unique identifier of the migration job driving this pipeline run. */
        String jobId,

        /** Unique identifier of the uploaded project being migrated. */
        String projectId,

        /**
         * User preference for output config format.
         * Defaults to {@code KEEP_ORIGINAL} if not specified.
         */
        ConfigFormatPreference configFormatPreference,

        // ── Populated by Agent 1 (Architecture Analyzer) ──────────────────

        /** Result of scanning the project root for build system, framework, config format. */
        DetectionResult detectionResult,

        /** All PubSub topic names found in the source project. */
        List<String> pubSubTopics,

        /** All PubSub subscription names found in the source project. */
        List<String> pubSubSubscriptions,

        /** Fully qualified class names of all PubSub listener classes found. */
        List<String> listenerClasses,

        /** Fully qualified class names of all PubSub publisher classes found. */
        List<String> publisherClasses,

        // ── Populated by Agent 3 (Core Migrator) ──────────────────────────

        /** All files produced by the migration — rewritten, created, or marked unchanged. */
        List<MigratedFile> migratedFiles

) {
    /**
     * Compact canonical constructor — replaces null lists with empty immutable lists
     * so callers never need null checks when iterating agent results.
     */
    public ProjectContext {
        pubSubTopics       = pubSubTopics       != null ? List.copyOf(pubSubTopics)       : List.of();
        pubSubSubscriptions= pubSubSubscriptions!= null ? List.copyOf(pubSubSubscriptions): List.of();
        listenerClasses    = listenerClasses    != null ? List.copyOf(listenerClasses)    : List.of();
        publisherClasses   = publisherClasses   != null ? List.copyOf(publisherClasses)   : List.of();
        migratedFiles      = migratedFiles      != null ? List.copyOf(migratedFiles)      : List.of();
    }
}
