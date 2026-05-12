package com.altrix.common.domain.model;

import java.io.Serializable;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.DetectedFramework;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * Immutable Value Object produced by the project detection phase.
 *
 * <p>Contains everything the migration agents need to know about the
 * structure of the uploaded project before touching any source file.
 *
 * <p>This is a DDD Value Object — equality is based on all field values,
 * not on identity. It is immutable after construction.
 */
@Builder
public record DetectionResult(

        @NotNull BuildSystem buildSystem,
        @NotNull ConfigFormat configFormat,
        @NotNull DetectedFramework framework,

        /**
         * List of module names found in {@code settings.gradle.kts},
         * {@code settings.gradle}, or {@code pom.xml}.
         * Contains a single entry {@code ":"} for single-module projects.
         */
        @NotNull List<String> modules

) implements Serializable {
    /**
     * Compact canonical constructor — defensive copy of the modules list
     * to guarantee immutability.
     */
    public DetectionResult {
        modules = List.copyOf(modules);
    }

    /** Returns true if this is a multi-module project. */
    public boolean isMultiModule() {
        return modules.size() > 1;
    }
}
