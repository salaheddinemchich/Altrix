package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;

/**
 * One {@code import} statement in a source file, type-resolved.
 *
 * <p>Stored as graph edges (rather than only as raw text on the file)
 * so the migrator can quickly answer "which files in the project import
 * {@code com.google.api.services.pubsub.Pubsub}?" — the data needed to
 * propagate a replacement strategy across the whole project.
 *
 * @param importerFqn FQN of the class doing the import.
 * @param targetFqn   the fully-qualified type imported.
 * @param staticImport true when the import line was {@code import static}.
 */
public record ImportEdge(
        String importerFqn,
        String targetFqn,
        boolean staticImport
) implements Serializable {

    public ImportEdge {
        if (importerFqn == null || importerFqn.isBlank()) throw new IllegalArgumentException("importerFqn required");
        if (targetFqn == null   || targetFqn.isBlank())   throw new IllegalArgumentException("targetFqn required");
    }
}
