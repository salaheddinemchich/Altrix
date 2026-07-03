package com.altrix.common.domain.model;

import com.altrix.common.domain.enums.JakartaMessagingTarget;

import java.io.Serializable;

import java.util.List;

/**
 * Output of {@code CoreMigratorAgent} (Agent 3).
 *
 * <p>The set of rewritten files plus a short summary of what changed.
 * Consumed by {@code SandboxValidatorAgent} (Agent 4) which compiles
 * and runs the artifact in a sandbox.
 */
public record MigrationArtifact(

        String projectId,

        /** Every file produced or modified by the migration. */
        List<MigratedFile> files,

        /** Human-readable summary of what the migrator changed. */
        String summary,

        /**
         * Carried from the {@code MigrationPlan} that produced this artifact so
         * {@code SemanticValidatorAgent} can branch on it without re-deriving it
         * (it runs on the calling thread, after the migrator's worker pool has
         * already finished — no {@code SandboxContext} ThreadLocal needed here).
         */
        JakartaMessagingTarget jakartaMessagingTarget

) implements Serializable {
    public MigrationArtifact {
        files = files != null ? List.copyOf(files) : List.of();
        summary = summary != null ? summary : "";
        jakartaMessagingTarget = jakartaMessagingTarget != null
                ? jakartaMessagingTarget : JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS;
    }

    public static MigrationArtifact empty(String projectId) {
        return new MigrationArtifact(projectId, List.of(), "", JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
    }
}
