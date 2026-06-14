package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;

/**
 * {@code subtypeFqn} {@code kind} {@code supertypeFqn} — a single
 * inheritance / implementation arrow in the {@link SemanticGraph}.
 *
 * <p>Two arrows are produced for {@code class Foo extends Bar implements Baz}:
 * one with {@link Kind#EXTENDS}, one with {@link Kind#IMPLEMENTS}.
 *
 * <p>External supertypes (e.g. {@code java.lang.Object},
 * {@code jakarta.ws.rs.core.Application}) are kept too — the migrator
 * needs to know "this class implements {@code jakarta.ws.rs.core.Application}"
 * to preserve JAX-RS wiring.
 *
 * @param subtypeFqn   FQN of the class doing the extending / implementing.
 * @param supertypeFqn FQN of the class / interface being extended /
 *                     implemented.
 * @param kind         {@link Kind#EXTENDS} or {@link Kind#IMPLEMENTS}.
 */
public record InheritanceEdge(
        String subtypeFqn,
        String supertypeFqn,
        Kind kind
) implements Serializable {

    public InheritanceEdge {
        if (subtypeFqn == null   || subtypeFqn.isBlank())   throw new IllegalArgumentException("subtypeFqn required");
        if (supertypeFqn == null || supertypeFqn.isBlank()) throw new IllegalArgumentException("supertypeFqn required");
        if (kind == null) throw new IllegalArgumentException("kind required");
    }

    public enum Kind { EXTENDS, IMPLEMENTS }
}
