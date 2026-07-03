package com.altrix.common.domain.model;

import java.io.Serializable;
import java.util.List;

/**
 * Output of {@code SandboxValidatorAgent} (Agent 4).
 *
 * <p>Verdict on whether the migrated artifact compiles and passes its tests
 * in a sandboxed environment. Real implementation lands in issues #16/#17.
 *
 * <p>{@link #findings} (added in #94) carries structured per-issue data
 * (runner id, severity, file, line, message).  {@link #failures} is kept
 * as the flat-string view for back-compat with the retry-context builder
 * and the markdown reporter that consume it today.  New consumers should
 * prefer {@code findings}.
 */
public record ValidationReport(

        String projectId,

        /** {@code true} if the sandbox build + tests passed. */
        boolean passed,

        /** Flat string view of ERROR-severity findings — kept for back-compat. */
        List<String> failures,

        String summary,

        /** Structured findings — empty list when no runners produced any. */
        List<Finding> findings

) implements Serializable {

    public ValidationReport {
        failures = failures != null ? List.copyOf(failures) : List.of();
        findings = findings != null ? List.copyOf(findings) : List.of();
        summary = summary != null ? summary : "";
    }

    /**
     * Back-compat constructor — pre-#94 call-sites that don't carry structured findings.
     */
    public ValidationReport(String projectId, boolean passed, List<String> failures, String summary) {
        this(projectId, passed, failures, summary, List.of());
    }

    public static ValidationReport pending(String projectId) {
        return new ValidationReport(projectId, true, List.of(), "validation skipped (stub)", List.of());
    }

    /**
     * Artifact-relative paths of the files implicated in ERROR-severity
     * {@link #findings}.  Consumed by the retry loop to narrow the next
     * migration attempt to the files that actually failed, instead of
     * re-sending healthy files through the LLM (which reliably corrupts
     * previously-correct output).
     *
     * <p>Paths are normalised: separators unified to {@code /}, any leading
     * sandbox {@code /workspace/} prefix and leading slashes stripped —
     * matching the artifact-relative form ({@code src/main/java/...}) that
     * {@code MigratedFile} paths use.  Deduplicated, insertion-ordered.
     *
     * <p>Empty when no runner produced per-file findings (project-wide
     * failures such as a boot timeout) — callers treat empty as
     * "no narrowing possible".
     */
    public List<String> failingFilePaths() {
        var out = new java.util.LinkedHashSet<String>();
        for (Finding f : findings) {
            if (!"ERROR".equalsIgnoreCase(f.severity())) continue;
            String p = f.filePath();
            if (p == null || p.isBlank()) continue;
            p = p.replace('\\', '/');
            int ws = p.indexOf("/workspace/");
            if (ws >= 0) p = p.substring(ws + "/workspace/".length());
            while (p.startsWith("/")) p = p.substring(1);
            if (!p.isBlank()) out.add(p);
        }
        return List.copyOf(out);
    }

    /**
     * One structured validation issue.  Mirrors the orchestrator-side
     * {@code SandboxFinding} but lives in {@code platform-common} so it can
     * cross service boundaries (frontend payload, future platform-report
     * consumer, etc.).
     *
     * @param runnerId stable id of the runner that emitted this finding
     *                 (e.g. {@code "static"}, {@code "docker"}, {@code "checkstyle"}).
     * @param severity {@code ERROR}, {@code WARNING}, or {@code INFO}.  Stored
     *                 as String so common stays free of orchestrator enums.
     * @param filePath repository-relative path; nullable for project-wide findings.
     * @param line     1-based line number; -1 when not known.
     * @param message  human-readable description.
     */
    public record Finding(
            String runnerId,
            String severity,
            String filePath,
            int line,
            String message
    ) implements Serializable {
        public Finding {
            runnerId = runnerId != null ? runnerId : "";
            severity = severity != null ? severity : "ERROR";
            message = message != null ? message : "";
        }
    }
}
