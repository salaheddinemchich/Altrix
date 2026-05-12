package com.altrix.common.domain.model;

import java.io.Serializable;

import com.altrix.common.domain.enums.FileChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * Immutable Value Object representing a single file produced by the migration.
 *
 * <p>Each instance describes one file that was touched during the migration:
 * its original path, its new path, the rewritten content, the type of change,
 * and a human-readable summary of what changed.
 *
 * <p>This is a DDD Value Object — no identity, equality by value.
 */
@Builder
public record MigratedFile(

        /** Original path relative to project root, e.g. {@code src/main/java/com/example/MyListener.java} */
        @NotBlank String originalPath,

        /** Path in the migrated output ZIP. Usually the same as originalPath. */
        @NotBlank String newPath,

        /** Full rewritten file content as a UTF-8 string. */
        @NotNull String content,

        /** What kind of change was applied to this file. */
        @NotNull FileChangeType changeType,

        /** Short human-readable description of what changed, shown in the report. */
        @NotBlank String diffSummary

) implements Serializable {}
