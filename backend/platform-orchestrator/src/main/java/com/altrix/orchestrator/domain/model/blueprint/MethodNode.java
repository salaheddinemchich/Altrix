package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.List;

/**
 * A method declared on a {@link ClassNode}.
 *
 * <p>Carries name + arity + parameter and return type information so the
 * migrator's prompt can render the method signature exactly as the
 * source declares it.  Stored separately from {@link ClassNode} so a
 * {@link CallEdge} can point at the resolved callee without dragging
 * the whole class along.
 *
 * @param ownerFqn       FQN of the class that declares the method.
 * @param name           method name.
 * @param parameterTypes ordered list of fully-qualified parameter types
 *                       (or {@code "?"} for unresolved generics).
 * @param returnType     fully-qualified return type, or {@code "void"}.
 * @param modifiers      {@code public}, {@code static}, {@code @Override},
 *                       and friends.  The validator + migrator both read
 *                       these to know whether to look at supertypes.
 */
public record MethodNode(
        String ownerFqn,
        String name,
        List<String> parameterTypes,
        String returnType,
        List<String> modifiers
) implements Serializable {

    public MethodNode {
        if (ownerFqn == null || ownerFqn.isBlank()) throw new IllegalArgumentException("ownerFqn required");
        if (name == null || name.isBlank())         throw new IllegalArgumentException("name required");
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        modifiers      = modifiers      == null ? List.of() : List.copyOf(modifiers);
        if (returnType == null || returnType.isBlank()) returnType = "void";
    }

    /** Renderable signature, e.g. {@code publish(PubsubTopic, AltrixPubsubMessage): void}. */
    public String signature() {
        return name + "(" + String.join(", ", parameterTypes) + "): " + returnType;
    }

    /** Stable identifier used as the foreign key from {@link CallEdge#calleeKey()}. */
    public String key() {
        return ownerFqn + "#" + name + "/" + parameterTypes.size();
    }
}
