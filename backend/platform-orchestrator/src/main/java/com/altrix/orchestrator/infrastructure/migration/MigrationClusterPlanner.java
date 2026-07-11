package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.domain.model.blueprint.ClassNode;
import com.altrix.orchestrator.domain.model.blueprint.InheritanceEdge;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups tightly-coupled project files into <b>migration clusters</b> so the
 * Core Migrator can rewrite a cluster in a SINGLE LLM call — keeping a shared
 * contract (interface + its implementors, or an abstract base + subclasses)
 * consistent <i>by construction</i> instead of letting independent per-file
 * rewrites diverge.
 *
 * <p>Clustering is driven purely by the blueprint's inheritance graph: an
 * {@code implements}/{@code extends} edge between two PROJECT-owned types
 * couples their declaring files.  Connected components of that relation become
 * clusters.  Components below 2 files (nothing to coordinate) or above
 * {@code maxClusterFiles} (too big for one prompt — migrate per-file instead)
 * are dropped.
 *
 * <p>Pure + deterministic; the response parser is a static helper so it can be
 * unit-tested without a live model.
 */
@Slf4j
@Component
public class MigrationClusterPlanner {

    /** Delimiter the cluster prompt asks the model to put before each file's content. */
    private static final Pattern FILE_MARKER = Pattern.compile("^={2,}\\s*FILE:\\s*(.+?)\\s*={2,}\\s*$", Pattern.MULTILINE);
    private final int maxClusterFiles;
    
    public MigrationClusterPlanner(@Value("${migration.cluster.max-files:6}") int maxClusterFiles) {
        this.maxClusterFiles = maxClusterFiles;
    }

    /**
     * @return clusters of repository-relative file paths to migrate together;
     *         empty when no blueprint or no coupled files.  Each cluster has
     *         2..{@code maxClusterFiles} distinct files.
     */
    public List<List<String>> plan(ProjectBlueprint blueprint) {
        if (blueprint == null || blueprint.semanticGraph() == null) return List.of();
        SemanticGraph g = blueprint.semanticGraph();

        Map<String, String> fileByFqn = new HashMap<>();
        for (ClassNode c : g.classes()) {
            if (c.isProjectOwned() && c.filePath() != null) fileByFqn.put(c.fqn(), c.filePath());
        }
        if (fileByFqn.isEmpty()) return List.of();

        UnionFind uf = new UnionFind();
        for (InheritanceEdge e : g.inheritance()) {
            if (fileByFqn.containsKey(e.subtypeFqn()) && fileByFqn.containsKey(e.supertypeFqn())) {
                uf.union(e.subtypeFqn(), e.supertypeFqn());
            }
        }

        Map<String, LinkedHashSet<String>> filesByRoot = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fileByFqn.entrySet()) {
            if (!uf.contains(e.getKey())) continue; // type not coupled to anything
            filesByRoot.computeIfAbsent(uf.find(e.getKey()), k -> new LinkedHashSet<>()).add(e.getValue());
        }

        List<List<String>> clusters = new ArrayList<>();
        for (LinkedHashSet<String> files : filesByRoot.values()) {
            if (files.size() >= 2 && files.size() <= maxClusterFiles) {
                clusters.add(new ArrayList<>(files));
            } else if (files.size() > maxClusterFiles) {
                log.debug("Cluster of {} files exceeds max {} — migrating per-file instead",
                        files.size(), maxClusterFiles);
            }
        }
        return clusters;
    }

    /**
     * Parses a cluster migration response of the form
     * <pre>
     * === FILE: a/B.java ===
     * &lt;content&gt;
     * === FILE: a/C.java ===
     * &lt;content&gt;
     * </pre>
     * into a {@code path → content} map.  Content before the first marker is
     * ignored (prose preamble).  Trailing whitespace per file is trimmed.
     * Pure — unit-tested without a model.
     */
    public static Map<String, String> parseResponse(String response) {
        Map<String, String> out = new LinkedHashMap<>();
        if (response == null || response.isBlank()) return out;
        Matcher m = FILE_MARKER.matcher(response);
        List<int[]> spans = new ArrayList<>();   // [contentStart, markerStart-for-next]
        List<String> paths = new ArrayList<>();
        int lastEnd = -1;
        String lastPath = null;
        while (m.find()) {
            if (lastPath != null) spans.add(new int[]{lastEnd, m.start()});
            paths.add(lastPath);
            lastPath = m.group(1).trim();
            lastEnd = m.end();
        }
        if (lastPath != null) {
            spans.add(new int[]{lastEnd, response.length()});
            paths.add(lastPath);
        }
        // paths.get(0) is the synthetic null preamble holder — skip it.
        for (int i = 1; i < paths.size(); i++) {
            String path = paths.get(i);
            int[] span = spans.get(i - 1);
            if (path == null || path.isBlank()) continue;
            out.put(path, response.substring(span[0], span[1]).strip());
        }
        return out;
    }

    /** Minimal string union-find. */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        boolean contains(String x) { return parent.containsKey(x); }

        String find(String x) {
            parent.putIfAbsent(x, x);
            String root = x;
            while (!parent.get(root).equals(root)) root = parent.get(root);
            while (!parent.get(x).equals(root)) { String next = parent.get(x); parent.put(x, root); x = next; }
            return root;
        }

        void union(String a, String b) { parent.put(find(a), find(b)); }
    }
}
