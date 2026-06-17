package com.altrix.orchestrator.domain.model.semantic;

import java.io.Serializable;
import java.util.List;

/**
 * Aggregated output of the SemanticValidator phase — every semantic issue
 * found in a {@code MigrationArtifact} BEFORE the expensive sandbox
 * compile.
 *
 * <p>Flat {@link Finding} DTOs rather than the raw validator violation
 * types so the report is a self-contained value object the UI / report
 * generator can consume without depending on
 * {@code ContractViolation} / {@code PubSubLeakViolation}.
 *
 * @param projectId the artifact's project id.
 * @param clean     true when no findings — the artifact may proceed to
 *                  sandbox compile with confidence.
 * @param findings  every semantic issue, across all categories.
 * @param summary   one-line human summary for logs + progress events.
 */
public record SemanticValidationReport(
        String projectId,
        boolean clean,
        List<Finding> findings,
        String summary
) implements Serializable {

    public SemanticValidationReport {
        findings = findings != null ? List.copyOf(findings) : List.of();
        if (summary == null) summary = "";
    }

    /** A single semantic issue. */
    public record Finding(
            Category category,
            String filePath,
            int line,
            String symbol,
            String message
    ) implements Serializable {
        public Finding {
            if (category == null) throw new IllegalArgumentException("category required");
            if (symbol == null) symbol = "";
            if (message == null) message = "";
        }
    }

    public enum Category {
        /** Cross-file contract issue (interface drift, unknown method, etc.) — from ContractValidator. */
        CONTRACT,
        /** Surviving GCP Pub/Sub artifact — from PubSubLeakValidator. */
        PUBSUB_LEAK,
        /** Import/symbol on the KafkaMigrationKnowledgeBase forbidden list. */
        FORBIDDEN_IMPORT,
        /** Code references a class whose Maven dependency is absent from the build — from DependencyValidator. */
        MISSING_DEPENDENCY,
        /**
         * Spring-Kafka over-engineering — a {@code @KafkaListener} class that also
         * hand-wires manual listener containers / consumer factories Spring Boot
         * already auto-configures.  Boot-breaking (unsatisfiable beans) or
         * redundant; the migrator should be constrained to the minimal form.
         * Flagged for the AI repair tier — NOT auto-deleted (too risky).
         */
        SPRING_OVERENGINEERING
    }

    public static SemanticValidationReport clean(String projectId, int fileCount) {
        return new SemanticValidationReport(projectId, true, List.of(),
                "Semantic validation passed — " + fileCount + " file(s), no issues");
    }

    /** Count of findings in a given category. */
    public long countOf(Category category) {
        return findings.stream().filter(f -> f.category() == category).count();
    }
}
