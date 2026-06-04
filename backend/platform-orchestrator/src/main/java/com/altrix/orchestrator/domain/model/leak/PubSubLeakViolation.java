package com.altrix.orchestrator.domain.model.leak;

/**
 * One Pub/Sub residue surfaced by the {@code PubSubLeakValidator}.
 *
 * <p>The shape mirrors {@code ContractViolation} but adds a
 * {@code suggestion} field carrying the Kafka replacement strategy.  The
 * suggestion is what the repair prompt feeds the model — pre-computed by
 * the validator from the violation kind + symbol so the LLM does not
 * have to "decide" the migration approach for each leak.
 *
 * @param kind        category of the leak.
 * @param filePath    path of the file the leak was found in.
 * @param line        1-based source line, or {@code -1} when unknown.
 * @param symbol      the offending identifier — FQN for imports, simple
 *                    name for types, method-call chain for chains.
 * @param reason      one-line explanation suitable for logs / structured
 *                    findings.
 * @param suggestion  the Kafka replacement strategy.  Goes into the
 *                    repair prompt verbatim so the model rewrites the
 *                    leak the way we intend, instead of inventing a new
 *                    approach per call.
 */
public record PubSubLeakViolation(
        PubSubLeakKind kind,
        String filePath,
        int line,
        String symbol,
        String reason,
        String suggestion
) {
    public PubSubLeakViolation {
        if (kind == null)       throw new IllegalArgumentException("kind required");
        if (filePath == null)   throw new IllegalArgumentException("filePath required");
        if (reason == null)     throw new IllegalArgumentException("reason required");
        if (symbol == null)     symbol = "";
        if (suggestion == null) suggestion = "";
    }

    /** Compact one-line representation used in logs and the LLM prompt. */
    public String toLine() {
        String location = line > 0 ? filePath + ":" + line : filePath;
        return "[" + kind.name() + "] " + location
                + (symbol.isEmpty() ? "" : " '" + symbol + "'")
                + " — " + reason
                + (suggestion.isEmpty() ? "" : "  -> " + suggestion);
    }
}
