package com.altrix.common.domain.model;

import java.io.Serializable;

import java.util.List;

/**
 * Output of {@code MigrationPlannerAgent} (Agent 2).
 *
 * <p>Carries the full migration specification: what to migrate, how risky it is,
 * how much effort is expected, and the {@code storageKey} the Core Migrator needs
 * to read the source files from MinIO (issue #5).
 *
 * <p>{@code targetFiles} is populated by the AI planner with paths it identified
 * for REWRITE. {@code ContextPruner} uses this list to filter the full file set
 * before passing to Agent 3, reducing AI context by ~75–85% (#27).
 * An empty list means the planner did not identify specific files — Agent 3 falls
 * back to PubSub-pattern detection over the full file set.
 */
public record MigrationPlan(

        String projectId,

        /** MinIO storage key of the uploaded ZIP — required by CoreMigratorAgent to read source files. */
        String storageKey,

        /** Target technology stack after migration, e.g. "Spring Boot 3 + Apache Kafka". */
        String targetStack,

        /** Ordered, human-readable description of each migration step. */
        List<String> steps,

        /** Assessed risk level: {@code LOW}, {@code MEDIUM}, or {@code HIGH}. */
        String riskLevel,

        /** Rough effort estimate, e.g. "3–5 days". */
        String estimatedEffort,

        /** Short summary persisted alongside the job and displayed in reports. */
        String summary,

        /**
         * File paths (relative, as stored in the ZIP) that Agent 2 determined require
         * REWRITE in this migration. Consumed by {@code ContextPruner} (#27).
         * Empty when the planner could not identify specific files.
         */
        List<String> targetFiles

) implements Serializable {
    public MigrationPlan {
        storageKey = storageKey != null ? storageKey : "";
        targetStack = targetStack != null ? targetStack : "";
        steps = steps != null ? List.copyOf(steps) : List.of();
        riskLevel = riskLevel != null ? riskLevel : "";
        estimatedEffort = estimatedEffort != null ? estimatedEffort : "";
        summary = summary != null ? summary : "";
        targetFiles = targetFiles != null ? List.copyOf(targetFiles) : List.of();
    }

    public static MigrationPlan empty(String projectId) {
        return new MigrationPlan(projectId, "", "", List.of(), "", "", "", List.of());
    }
}
