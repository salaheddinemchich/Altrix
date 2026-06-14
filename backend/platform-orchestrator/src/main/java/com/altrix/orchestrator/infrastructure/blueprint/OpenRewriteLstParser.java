package com.altrix.orchestrator.infrastructure.blueprint;

import lombok.extern.slf4j.Slf4j;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Parses a project's Java sources into OpenRewrite Lossless Semantic Trees
 * (LSTs).  Feeding every source unit to a single parser instance lets
 * OpenRewrite cross-reference intra-project types — which is what gives
 * the downstream {@link LstSemanticGraphBuilder} type-attributed
 * relationships (who implements what, who calls whom) without a full
 * external classpath.
 *
 * <p>External library types (kafka-clients, jakarta-api, …) resolve to
 * "unknown" type without their JARs on the parser classpath; the graph
 * builder records those references but marks them not-project-owned.  For
 * the migrator's needs — understanding the PROJECT's own structure — that
 * is the 80% that matters; full external attribution is a later upgrade
 * (add the resolved Maven classpath to {@link JavaParser.Builder#classpath}).
 *
 * <p>Resilient: a source that fails to parse is logged + skipped, never
 * aborting the whole run.  Parse errors surface later at sandbox compile.
 */
@Slf4j
@Component
public class OpenRewriteLstParser {

    /**
     * @param javaSources path → source content for every {@code .java}
     *                    file in the project.  Non-Java entries are ignored
     *                    by the caller before this point.
     * @return the parsed compilation units; empty when nothing parsed.
     */
    public List<J.CompilationUnit> parse(Map<String, String> javaSources) {
        return parse(javaSources, List.of());
    }

    /**
     * Parses with a resolved dependency classpath so external library types
     * (kafka-clients, jakarta-api, …) are fully type-attributed — not just
     * intra-project types.  Pass the JARs from
     * {@link MavenClasspathResolver#resolve(String)}; an empty list degrades
     * to the intra-project-only behaviour of {@link #parse(Map)}.
     *
     * @param classpath dependency JAR paths on the host, or empty for none.
     */
    public List<J.CompilationUnit> parse(Map<String, String> javaSources, Collection<Path> classpath) {
        if (javaSources == null || javaSources.isEmpty()) return List.of();

        List<Parser.Input> inputs = new ArrayList<>(javaSources.size());
        for (Map.Entry<String, String> e : javaSources.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            Path p = Path.of(path);
            inputs.add(Parser.Input.fromString(p, content));
        }
        if (inputs.isEmpty()) return List.of();

        ExecutionContext ctx = new InMemoryExecutionContext(
                t -> log.debug("OpenRewrite parse warning: {}", t.getMessage()));
        JavaParser.Builder<?, ?> builder = JavaParser.fromJavaVersion();
        if (classpath != null && !classpath.isEmpty()) {
            builder.classpath(classpath);
            log.info("OpenRewrite LST classpath: {} dependency JAR(s) attached", classpath.size());
        }
        JavaParser parser = builder.build();

        List<J.CompilationUnit> units = new ArrayList<>();
        try {
            parser.parseInputs(inputs, null, ctx)
                    .filter(J.CompilationUnit.class::isInstance)
                    .map(J.CompilationUnit.class::cast)
                    .forEach(units::add);
        } catch (Exception ex) {
            log.warn("OpenRewrite parse failed ({}); returning {} unit(s) parsed before failure",
                    ex.getMessage(), units.size());
        }
        log.info("OpenRewrite parsed {} of {} Java source(s) into LSTs",
                units.size(), inputs.size());
        return units;
    }
}
