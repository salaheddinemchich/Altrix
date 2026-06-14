package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.List;

/**
 * The immediate neighbours of a class in the project's type graph.
 *
 * <p>Carries enough information for the migrator's per-file prompt to
 * say "this class implements X, depends on Y, is called by Z" without
 * having to walk the full {@link SemanticGraph} every time.  Populated
 * by the LST walker from type-attributed nodes.
 *
 * @param extendsType    fully-qualified name of the superclass — null
 *                       when the class has no explicit superclass.
 * @param implementsTypes fully-qualified names of every directly-implemented
 *                       interface.
 * @param dependsOn      fully-qualified names of every type this class
 *                       refers to as a field, parameter, return, local
 *                       variable, or method receiver.  Limited to types
 *                       owned by the project (external libraries omitted).
 * @param calledBy       simple class names of every class in the project
 *                       that calls a method on this class.  Used as the
 *                       "callers list" in the migrator prompt so a rename
 *                       is never one-sided.
 */
public record BlueprintRelationships(
        String extendsType,
        List<String> implementsTypes,
        List<String> dependsOn,
        List<String> calledBy
) implements Serializable {

    public BlueprintRelationships {
        implementsTypes = implementsTypes == null ? List.of() : List.copyOf(implementsTypes);
        dependsOn       = dependsOn       == null ? List.of() : List.copyOf(dependsOn);
        calledBy        = calledBy        == null ? List.of() : List.copyOf(calledBy);
    }

    public static BlueprintRelationships empty() {
        return new BlueprintRelationships(null, List.of(), List.of(), List.of());
    }
}
