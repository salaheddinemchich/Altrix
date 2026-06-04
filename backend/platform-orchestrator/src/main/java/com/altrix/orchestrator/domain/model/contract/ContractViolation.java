package com.altrix.orchestrator.domain.model.contract;

/**
 * One cross-file inconsistency surfaced by the {@code ContractValidator}.
 *
 * <p>The record is intentionally flat — easy to serialise into a prompt
 * for the minimal-patch repair loop, easy to group by file, easy to log.
 *
 * @param kind        category of the violation (one of {@link ContractViolationKind}).
 * @param filePath    path of the file the violation was found in.  Stable
 *                    across iterations so the repair loop can target it.
 * @param line        1-based source line, or {@code -1} when the location
 *                    is unknown (e.g. file-level violations).
 * @param symbol      the offending identifier — class name, method name,
 *                    import FQN, etc.  Goes in the LLM prompt verbatim.
 * @param message     human-readable explanation.  Goes in logs and into
 *                    the repair prompt so the model knows exactly what
 *                    to fix.  Must be self-contained — the prompt sees
 *                    nothing else.
 */
public record ContractViolation(
        ContractViolationKind kind,
        String filePath,
        int line,
        String symbol,
        String message
) {
    public ContractViolation {
        if (kind == null)     throw new IllegalArgumentException("kind required");
        if (filePath == null) throw new IllegalArgumentException("filePath required");
        if (message == null)  throw new IllegalArgumentException("message required");
        if (symbol == null)   symbol = "";
    }

    /** Compact one-line representation used in logs and the LLM prompt. */
    public String toLine() {
        String location = line > 0 ? filePath + ":" + line : filePath;
        return "[" + kind.name() + "] " + location
                + (symbol.isEmpty() ? "" : " '" + symbol + "'")
                + " — " + message;
    }
}
