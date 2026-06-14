package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;

/**
 * A type-attributed method invocation in the project — "{@code callerFqn}'s
 * code at {@code line} calls {@code calleeKey}".
 *
 * <p>The {@code calleeKey} matches {@link MethodNode#key()} when the call
 * resolves to a project-owned method.  For external library calls (e.g.
 * {@code Lombok @Slf4j-generated log.info}) the {@code calleeKey} carries
 * the external FQN + signature so the migrator can still see what is
 * being used, even though no full {@link MethodNode} exists.
 *
 * @param callerFqn   FQN of the class whose code performs the call.
 * @param callerLine  1-based source line in the caller; -1 when unknown.
 * @param calleeKey   identifier matching {@link MethodNode#key()} for
 *                    project-owned calls; opaque FQN+sig string for
 *                    external library calls.
 * @param projectOwned true when {@code calleeKey} resolves to a
 *                    {@link MethodNode} inside this project.
 */
public record CallEdge(
        String callerFqn,
        int callerLine,
        String calleeKey,
        boolean projectOwned
) implements Serializable {

    public CallEdge {
        if (callerFqn == null || callerFqn.isBlank()) throw new IllegalArgumentException("callerFqn required");
        if (calleeKey == null || calleeKey.isBlank()) throw new IllegalArgumentException("calleeKey required");
    }
}
