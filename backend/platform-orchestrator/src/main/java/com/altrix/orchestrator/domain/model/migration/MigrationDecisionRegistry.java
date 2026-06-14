package com.altrix.orchestrator.domain.model.migration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Session-scoped, append-only record of every {@link MigrationDecision}
 * made while migrating a project.  Immutable — mutation returns a new
 * instance via {@link #withDecision} / {@link #withDecisions} so the
 * aggregate stays a value object; the persistence adapter handles the
 * load → append → save cycle.
 *
 * <p>Read by the migrator's per-file prompt builder and by the
 * SemanticValidator's consistency checks: "file X references type
 * {@code Foo}; has anyone decided to replace {@code Foo}? — if so, X must
 * use the replacement".
 *
 * @param sessionId workflow-session id the decisions belong to.
 * @param decisions append-only list, oldest first.
 */
public record MigrationDecisionRegistry(
        String sessionId,
        List<MigrationDecision> decisions
) implements Serializable {

    public MigrationDecisionRegistry {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId required");
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /** An empty registry for a session that has made no decisions yet. */
    public static MigrationDecisionRegistry empty(String sessionId) {
        return new MigrationDecisionRegistry(sessionId, List.of());
    }

    // ── Mutation (returns new instance) ──────────────────────────────────────

    public MigrationDecisionRegistry withDecision(MigrationDecision decision) {
        List<MigrationDecision> next = new ArrayList<>(decisions);
        next.add(decision);
        return new MigrationDecisionRegistry(sessionId, next);
    }

    public MigrationDecisionRegistry withDecisions(List<MigrationDecision> more) {
        if (more == null || more.isEmpty()) return this;
        List<MigrationDecision> next = new ArrayList<>(decisions);
        next.addAll(more);
        return new MigrationDecisionRegistry(sessionId, next);
    }

    // ── Lookups (the consistency-enforcement read patterns) ──────────────────

    /** The replacement decided for a type, or empty when none recorded. */
    public Optional<String> typeReplacement(String fromType) {
        return latest(MigrationDecision.Kind.REPLACE_TYPE, fromType).map(MigrationDecision::to);
    }

    /** The rename decided for a class, or empty when none recorded. */
    public Optional<String> classRename(String fromFqn) {
        return latest(MigrationDecision.Kind.RENAME_CLASS, fromFqn).map(MigrationDecision::to);
    }

    /** The dependency substitution decided, or empty when none recorded. */
    public Optional<String> dependencyReplacement(String fromCoord) {
        return latest(MigrationDecision.Kind.REPLACE_DEPENDENCY, fromCoord).map(MigrationDecision::to);
    }

    /** True when any decision of {@code kind} exists for {@code from}. */
    public boolean hasDecision(MigrationDecision.Kind kind, String from) {
        return latest(kind, from).isPresent();
    }

    /**
     * The most-recent decision of a given kind for a given symbol.  Last
     * write wins — if a later file overrode an earlier decision, the
     * later one is authoritative.
     */
    public Optional<MigrationDecision> latest(MigrationDecision.Kind kind, String from) {
        MigrationDecision found = null;
        for (MigrationDecision d : decisions) {
            if (d.kind() == kind && d.from().equals(from)) found = d;
        }
        return Optional.ofNullable(found);
    }

    public boolean isEmpty() {
        return decisions.isEmpty();
    }
}
