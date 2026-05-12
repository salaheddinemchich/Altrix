package com.altrix.common.domain.model;

import java.io.Serializable;

import java.util.List;

/**
 * Output of {@code SandboxValidatorAgent} (Agent 4).
 *
 * <p>Verdict on whether the migrated artifact compiles and passes its tests
 * in a sandboxed environment. Real implementation lands in issues #16/#17.
 */
public record ValidationReport(

        String projectId,

        /** {@code true} if the sandbox build + tests passed. */
        boolean passed,

        /** Compilation errors, test failures, or other validation findings. */
        List<String> failures,

        String summary

) implements Serializable {
    public ValidationReport {
        failures = failures != null ? List.copyOf(failures) : List.of();
        summary  = summary  != null ? summary : "";
    }

    public static ValidationReport pending(String projectId) {
        return new ValidationReport(projectId, true, List.of(), "validation skipped (stub)");
    }
}
