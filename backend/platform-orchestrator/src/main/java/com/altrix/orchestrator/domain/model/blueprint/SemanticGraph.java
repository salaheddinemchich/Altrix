package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The project-wide, type-attributed semantic graph extracted by walking
 * the OpenRewrite LST of every source file.
 *
 * <p>Four collections are stored explicitly because they each answer a
 * different question the migrator's per-file prompt needs to ask:
 * <ul>
 *   <li>{@link #classes()}      — "what does this class look like?"</li>
 *   <li>{@link #methods()}      — "what is the exact signature of this method?"</li>
 *   <li>{@link #inheritance()}  — "what does this class extend / implement?"</li>
 *   <li>{@link #calls()}        — "who calls whom?"</li>
 *   <li>{@link #imports()}      — "which files import this type?"</li>
 * </ul>
 *
 * <p>Lookups are O(n) on raw record lists; the {@link #callersOf(String)},
 * {@link #calleesOf(String)}, {@link #methodsOf(String)},
 * {@link #importersOf(String)} helpers exist so the migrator's prompt
 * builder doesn't litter the call site with stream chains.
 */
public record SemanticGraph(
        List<ClassNode> classes,
        List<MethodNode> methods,
        List<InheritanceEdge> inheritance,
        List<CallEdge> calls,
        List<ImportEdge> imports
) implements Serializable {

    public SemanticGraph {
        classes     = classes     == null ? List.of() : List.copyOf(classes);
        methods     = methods     == null ? List.of() : List.copyOf(methods);
        inheritance = inheritance == null ? List.of() : List.copyOf(inheritance);
        calls       = calls       == null ? List.of() : List.copyOf(calls);
        imports     = imports     == null ? List.of() : List.copyOf(imports);
    }

    public static SemanticGraph empty() {
        return new SemanticGraph(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // ── Convenience views (computed each call — cheap on the modest
    //     graphs this project produces; if perf becomes a concern we'd
    //     pre-index in the JPA adapter on read). ──

    /** All methods declared on {@code ownerFqn}. */
    public List<MethodNode> methodsOf(String ownerFqn) {
        return methods.stream().filter(m -> ownerFqn.equals(m.ownerFqn())).toList();
    }

    /** Simple class names of every project class that calls a method on {@code calleeOwnerFqn}. */
    public Set<String> callersOf(String calleeOwnerFqn) {
        return calls.stream()
                .filter(e -> e.calleeKey().startsWith(calleeOwnerFqn + "#"))
                .map(CallEdge::callerFqn)
                .map(SemanticGraph::simpleNameOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Project FQNs the methods of {@code callerFqn} call into. */
    public Set<String> calleesOf(String callerFqn) {
        return calls.stream()
                .filter(e -> callerFqn.equals(e.callerFqn()) && e.projectOwned())
                .map(e -> {
                    int hash = e.calleeKey().indexOf('#');
                    return hash > 0 ? e.calleeKey().substring(0, hash) : e.calleeKey();
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Project FQNs that import {@code targetFqn}. */
    public Set<String> importersOf(String targetFqn) {
        return imports.stream()
                .filter(e -> targetFqn.equals(e.targetFqn()))
                .map(ImportEdge::importerFqn)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Group every {@link ClassNode} by its declaring source file path. */
    public Map<String, List<ClassNode>> classesByFile() {
        Map<String, List<ClassNode>> grouped = new LinkedHashMap<>();
        for (ClassNode c : classes) {
            if (c.filePath() == null) continue;
            grouped.computeIfAbsent(c.filePath(), k -> new java.util.ArrayList<>()).add(c);
        }
        return Map.copyOf(grouped);
    }

    /** Strips package, keeps only the trailing simple name. */
    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }
}
