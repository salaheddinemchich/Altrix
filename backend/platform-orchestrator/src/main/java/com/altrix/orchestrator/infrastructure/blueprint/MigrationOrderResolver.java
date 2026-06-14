package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.ClassNode;
import com.altrix.orchestrator.domain.model.blueprint.InheritanceEdge;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes a migration order over the project's classes — leaves (types
 * with no project-owned dependencies) first — so that when the migrator
 * rewrites a class, every type it depends on has already been migrated and
 * the new symbols resolve.
 *
 * <p>A class {@code A} depends on {@code B} when {@code A} extends /
 * implements {@code B}, or calls a method on {@code B} (project-owned
 * edges only — external library types are irrelevant to ordering).
 *
 * <p>Kahn's algorithm.  Cycles (mutual dependencies) can't be fully
 * ordered; the resolver breaks them by appending the still-unresolved
 * classes in stable name order and recording the cycle so the caller can
 * add a risk note.
 *
 * <p>Output is a list of {@code filePath}s (not FQNs) so it lines up with
 * what the migrator iterates over.
 */
@Slf4j
@Component
public class MigrationOrderResolver {

    /**
     * @return file paths in migration order (leaves first).  Files whose
     *         classes are part of a dependency cycle come last.
     */
    public List<String> resolve(SemanticGraph graph) {
        if (graph == null || graph.classes().isEmpty()) return List.of();

        // fqn → filePath, and the set of project FQNs.
        Map<String, String> fileByFqn = new HashMap<>();
        Set<String> projectFqns = new HashSet<>();
        for (ClassNode c : graph.classes()) {
            projectFqns.add(c.fqn());
            if (c.filePath() != null) fileByFqn.put(c.fqn(), c.filePath());
        }

        // Build the dependency set per class (project-owned only).
        Map<String, Set<String>> deps = new HashMap<>();
        for (String fqn : projectFqns) deps.put(fqn, new HashSet<>());

        for (InheritanceEdge e : graph.inheritance()) {
            if (projectFqns.contains(e.subtypeFqn()) && projectFqns.contains(e.supertypeFqn())
                    && !e.subtypeFqn().equals(e.supertypeFqn())) {
                deps.get(e.subtypeFqn()).add(e.supertypeFqn());
            }
        }
        graph.calls().forEach(call -> {
            if (!call.projectOwned()) return;
            String caller = call.callerFqn();
            String calleeOwner = ownerOf(call.calleeKey());
            if (projectFqns.contains(caller) && projectFqns.contains(calleeOwner)
                    && !caller.equals(calleeOwner)) {
                deps.get(caller).add(calleeOwner);
            }
        });

        // Kahn: repeatedly emit classes whose remaining deps are all emitted.
        List<String> orderedFqns = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        // Stable processing order for deterministic output.
        List<String> remaining = new ArrayList<>(projectFqns);
        remaining.sort(String::compareTo);

        boolean progress = true;
        while (progress && emitted.size() < projectFqns.size()) {
            progress = false;
            for (String fqn : remaining) {
                if (emitted.contains(fqn)) continue;
                if (emitted.containsAll(deps.get(fqn))) {
                    orderedFqns.add(fqn);
                    emitted.add(fqn);
                    progress = true;
                }
            }
        }

        // Anything left is in a cycle — append in name order.
        if (emitted.size() < projectFqns.size()) {
            List<String> cyclic = new ArrayList<>();
            for (String fqn : remaining) if (!emitted.contains(fqn)) cyclic.add(fqn);
            log.warn("Migration order: {} class(es) in a dependency cycle, appended last: {}",
                    cyclic.size(), cyclic);
            orderedFqns.addAll(cyclic);
        }

        // FQNs → file paths, deduped (a file may declare several classes).
        Set<String> orderedFiles = new LinkedHashSet<>();
        for (String fqn : orderedFqns) {
            String path = fileByFqn.get(fqn);
            if (path != null) orderedFiles.add(path);
        }
        return new ArrayList<>(orderedFiles);
    }

    /** Returns true when the graph has a project-owned dependency cycle. */
    public boolean hasCycle(SemanticGraph graph) {
        if (graph == null || graph.classes().isEmpty()) return false;
        Set<String> projectFqns = new HashSet<>();
        for (ClassNode c : graph.classes()) projectFqns.add(c.fqn());

        Map<String, Set<String>> adj = new HashMap<>();
        for (String fqn : projectFqns) adj.put(fqn, new HashSet<>());
        for (InheritanceEdge e : graph.inheritance()) {
            if (projectFqns.contains(e.subtypeFqn()) && projectFqns.contains(e.supertypeFqn())) {
                adj.get(e.subtypeFqn()).add(e.supertypeFqn());
            }
        }
        graph.calls().forEach(call -> {
            String owner = ownerOf(call.calleeKey());
            if (call.projectOwned() && projectFqns.contains(call.callerFqn())
                    && projectFqns.contains(owner)) {
                adj.get(call.callerFqn()).add(owner);
            }
        });

        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String start : projectFqns) {
            if (done.contains(start)) continue;
            if (dfsCycle(start, adj, visiting, done)) return true;
        }
        return false;
    }

    private boolean dfsCycle(String node, Map<String, Set<String>> adj,
                             Set<String> visiting, Set<String> done) {
        if (done.contains(node)) return false;
        if (!visiting.add(node)) return true;
        for (String next : adj.getOrDefault(node, Set.of())) {
            if (!next.equals(node) && dfsCycle(next, adj, visiting, done)) return true;
        }
        visiting.remove(node);
        done.add(node);
        return false;
    }

    private static String ownerOf(String calleeKey) {
        int hash = calleeKey.indexOf('#');
        return hash > 0 ? calleeKey.substring(0, hash) : calleeKey;
    }
}
