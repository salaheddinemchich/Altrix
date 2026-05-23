package com.altrix.orchestrator.domain.model.sandbox;

/**
 * A single issue raised by a {@link com.altrix.orchestrator.domain.port.out.SandboxRunnerPort}
 * against a migrated artifact.  Structured on purpose: subsequent UI work
 * (#106 log viewer, future ValidationReport extension) needs runner +
 * severity + location to render properly.
 *
 * <p>Framework-free — lives in the domain layer.
 *
 * @param runnerId stable identifier of the runner that emitted this finding
 *                 (e.g. {@code "static"}, {@code "docker"}, {@code "checkstyle"}).
 * @param severity ERROR fails the validation; WARNING is informational only.
 * @param filePath repository-relative path the finding is about — may be null
 *                 for project-wide issues (e.g. "no tests ran").
 * @param line     1-based line number when known; -1 otherwise.
 * @param message  human-readable description.
 */
public record SandboxFinding(
        String runnerId,
        Severity severity,
        String filePath,
        int line,
        String message
) {

    public enum Severity {
        /** Blocks validation — the migrated code can't be accepted as-is. */
        ERROR,
        /** Worth showing to the reviewer but doesn't block. */
        WARNING,
        /** Purely informational (e.g. "no tests detected"). */
        INFO
    }

    /** Convenience constructor for project-wide findings (no file / line). */
    public static SandboxFinding of(String runnerId, Severity severity, String message) {
        return new SandboxFinding(runnerId, severity, null, -1, message);
    }

    /** Convenience constructor for file-scoped findings. */
    public static SandboxFinding ofFile(String runnerId, Severity severity, String filePath, String message) {
        return new SandboxFinding(runnerId, severity, filePath, -1, message);
    }

    /**
     * Renders the finding as a single line suitable for the legacy
     * {@code ValidationReport.failures} list.  Format chosen so the existing
     * "{path}: {message}" presentation pre-#16 still works.
     */
    public String toFailureLine() {
        StringBuilder sb = new StringBuilder();
        if (filePath != null && !filePath.isBlank()) sb.append(filePath).append(": ");
        sb.append(message);
        if (line > 0) sb.append(" (line ").append(line).append(')');
        return sb.toString();
    }
}
