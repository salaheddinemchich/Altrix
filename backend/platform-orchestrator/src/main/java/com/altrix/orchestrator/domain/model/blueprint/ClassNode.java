package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.List;

/**
 * A class / interface / enum / record node in the {@link SemanticGraph}.
 *
 * <p>Populated by the LST walker — every entry carries enough type
 * attribution that the migrator's prompt can produce a "what this class
 * looks like to the rest of the project" summary without re-parsing.
 *
 * @param fqn            fully-qualified type name — primary key inside the graph.
 * @param simpleName     trailing component of {@link #fqn}.
 * @param packageName    everything before the simple name (or {@code ""} for default).
 * @param kind           type kind.
 * @param filePath       repository-relative path of the source file that
 *                       declares it; matches {@link BlueprintFile#path()}.
 * @param isProjectOwned true when the type is declared inside the
 *                       uploaded project; false for external library types
 *                       referenced from project code.  The graph only
 *                       stores external classes that are referenced —
 *                       used by the migrator to know "this method call
 *                       lands on a library type, not a project type".
 * @param modifiers      relevant top-level modifiers ({@code public},
 *                       {@code abstract}, {@code sealed}, etc.).  Used by
 *                       the file/class-name validator and by Lombok
 *                       inference downstream.
 * @param annotations    simple names of top-level annotations
 *                       ({@code @Singleton}, {@code @ApplicationScoped},
 *                       {@code @Vetoed}, …).  Used to infer the file's
 *                       {@link BlueprintFile#role()}.
 */
public record ClassNode(
        String fqn,
        String simpleName,
        String packageName,
        BlueprintFile.Kind kind,
        String filePath,
        boolean isProjectOwned,
        List<String> modifiers,
        List<String> annotations
) implements Serializable {

    public ClassNode {
        if (fqn == null || fqn.isBlank())          throw new IllegalArgumentException("fqn required");
        if (simpleName == null || simpleName.isBlank()) throw new IllegalArgumentException("simpleName required");
        modifiers   = modifiers   == null ? List.of() : List.copyOf(modifiers);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
