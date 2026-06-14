package com.altrix.orchestrator.domain.model.migration;

import java.io.Serializable;
import java.time.Instant;

/**
 * One migration decision recorded during a session — the unit of the
 * {@link MigrationDecisionRegistry}.
 *
 * <p>Capability 8 of the SemanticValidator design: once a decision is
 * made for one file (e.g. "the {@code Pubsub} client is replaced by a
 * {@code KafkaProducer} + {@code KafkaConsumer} pair"), every later file
 * MUST follow the same decision.  Recording them centrally is what kills
 * cross-file drift at the source rather than catching it after compile.
 *
 * @param kind      category of decision (see {@link Kind}).
 * @param from      the original symbol — FQN for types, simple name for
 *                  methods, dotted path for packages, {@code group:artifact}
 *                  for dependencies.
 * @param to        the migrated symbol; may be empty when the decision is
 *                  a removal, and may equal {@code from} when the decision
 *                  is an explicit "keep this name" (recorded so later files
 *                  don't second-guess it).
 * @param scope     optional qualifier — the owning class FQN for a method
 *                  rename, or the file path the decision originated from.
 *                  Null when the decision is project-global.
 * @param rationale free-text reason; surfaced in the UI + fed to the
 *                  migrator prompt as a soft hint.
 * @param decidedAt when the decision was first recorded.
 */
public record MigrationDecision(
        Kind kind,
        String from,
        String to,
        String scope,
        String rationale,
        Instant decidedAt
) implements Serializable {

    public MigrationDecision {
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (from == null || from.isBlank()) throw new IllegalArgumentException("from required");
        if (to == null) to = "";
        if (decidedAt == null) decidedAt = Instant.now();
    }

    public enum Kind {
        /** A class/interface/enum was renamed (or explicitly kept). */
        RENAME_CLASS,
        /** A method was renamed on a given owner type ({@code scope} = owner FQN). */
        RENAME_METHOD,
        /** A package was moved. */
        MOVE_PACKAGE,
        /** A type was replaced by a different type/idiom (e.g. Pubsub → KafkaProducer). */
        REPLACE_TYPE,
        /** A build dependency was substituted (e.g. google-api-services-pubsub → kafka-clients). */
        REPLACE_DEPENDENCY,
        /** A Pub/Sub API call was replaced by a Kafka one (e.g. acknowledge → commitSync). */
        REPLACE_API
    }

    /** Convenience factories — keep call sites terse + intention-revealing. */
    public static MigrationDecision renameClass(String fromFqn, String toFqn, String rationale) {
        return new MigrationDecision(Kind.RENAME_CLASS, fromFqn, toFqn, null, rationale, Instant.now());
    }

    public static MigrationDecision replaceType(String fromType, String toType, String rationale) {
        return new MigrationDecision(Kind.REPLACE_TYPE, fromType, toType, null, rationale, Instant.now());
    }

    public static MigrationDecision replaceDependency(String fromCoord, String toCoord, String rationale) {
        return new MigrationDecision(Kind.REPLACE_DEPENDENCY, fromCoord, toCoord, null, rationale, Instant.now());
    }

    public static MigrationDecision replaceApi(String fromCall, String toCall, String scope, String rationale) {
        return new MigrationDecision(Kind.REPLACE_API, fromCall, toCall, scope, rationale, Instant.now());
    }
}
