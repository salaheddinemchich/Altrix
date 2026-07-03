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
        SPRING_OVERENGINEERING,
        /**
         * Jakarta EE + Spring Kafka hybrid only — a CDI persistence/business
         * class reached from a Spring {@code @KafkaListener} via
         * {@code CdiLookup.get(...)} that is still {@code @ApplicationScoped}
         * instead of {@code @Stateless}, or a {@code CdiLookup.get(...)} call
         * whose target cannot be statically resolved at all.  A Kafka
         * consumer thread carries no JTA transaction; only an EJB proxy
         * boundary creates one.  Defense-in-depth for whatever
         * {@code CdiStatelessConverter}'s static scan in the migrator
         * couldn't already auto-fix — flagged for the AI repair tier, not
         * auto-deleted.
         */
        SPRING_KAFKA_TX_GAP,
        /**
         * Jakarta EE + Spring Kafka hybrid only — {@code SPRING_KAFKA_HYBRID}
         * was selected but the final migrated artifact contains no
         * {@code @KafkaListener} at all, so the deterministic
         * {@code HybridScaffoldingGenerator} produced no bridge classes
         * ({@code SpringKafkaConfig} / {@code CdiLookup} / …). A hybrid
         * migration with zero listeners means something upstream went wrong —
         * every former Pub/Sub consumer should have become a
         * {@code @KafkaListener}. Flagged for the AI repair tier / human
         * review, not auto-fixed.
         */
        SPRING_KAFKA_NO_LISTENERS_FOUND,
        /**
         * Jakarta EE + Spring Kafka hybrid only — a migrated file drifted
         * toward the raw kafka-clients pattern (constructing
         * {@code KafkaProducer} / {@code KafkaConsumer} / {@code AdminClient}
         * directly, or calling {@code producerProperties()} /
         * {@code consumerProperties()} / {@code adminProperties()}) instead of
         * the hybrid target's {@code KafkaTemplate} / {@code @KafkaListener}
         * idiom. The rewrite into {@code KafkaTemplate.send(...)} is too
         * creative to auto-fix safely (it would risk reproducing the exact
         * hallucination this defends against), so it is detect-only — flagged
         * for the AI repair tier with a precise, mechanical instruction.
         */
        SPRING_KAFKA_TARGET_DRIFT,
        /**
         * Jakarta EE + Spring Kafka hybrid only — a Pub/Sub consumer that the
         * deterministic {@code HybridConsumerTransformer} could not fully
         * convert, fired <b>per method</b> (distinct from
         * {@code SPRING_KAFKA_NO_LISTENERS_FOUND}, which fires only when ZERO
         * consumers convert). Two shapes:
         * <ul>
         *   <li>a {@code @KafkaListener} method whose body still calls
         *       {@code pubsub.publish(...)} — the deterministic transform
         *       converted the method's structure but deferred the
         *       publish→{@code KafkaTemplate.send} rewrite (a message-shape
         *       judgement call) to a targeted retry;</li>
         *   <li>a method still calling {@code PubSubService.consume(...)} — the
         *       class was left for the LLM (shape mismatch / no topic binding).</li>
         * </ul>
         * Detect-only with a precise, mechanical retry instruction.
         */
        SPRING_KAFKA_CONSUMER_NOT_CONVERTED
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
