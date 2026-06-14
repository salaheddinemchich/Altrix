package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.ClassNode;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real OpenRewrite parser + graph builder against a small
 * synthetic project so we know the LST walk produces the relationships
 * the migrator depends on (implements, calls, imports, kind).
 */
class LstSemanticGraphBuilderTest {

    private final OpenRewriteLstParser parser = new OpenRewriteLstParser();
    private final LstSemanticGraphBuilder builder = new LstSemanticGraphBuilder();

    private SemanticGraph graphOf(Map<String, String> sources) {
        return builder.build(parser.parse(sources));
    }

    /** A 3-file project: an interface, its impl, and a caller. */
    private Map<String, String> sampleProject() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("src/main/java/com/x/PubsubService.java", """
                package com.x;
                public interface PubsubService {
                    void publish(String topic, String value);
                }
                """);
        p.put("src/main/java/com/x/PubsubServiceImpl.java", """
                package com.x;
                public class PubsubServiceImpl implements PubsubService {
                    @Override public void publish(String topic, String value) {}
                }
                """);
        p.put("src/main/java/com/x/OrderService.java", """
                package com.x;
                public class OrderService {
                    private final PubsubService svc = new PubsubServiceImpl();
                    void run() { svc.publish("t", "v"); }
                }
                """);
        return p;
    }

    @Test
    void emptyInputProducesEmptyGraph() {
        assertThat(builder.build(null)).isEqualTo(SemanticGraph.empty());
        assertThat(graphOf(Map.of())).isEqualTo(SemanticGraph.empty());
    }

    @Test
    void indexesAllProjectClasses() {
        SemanticGraph g = graphOf(sampleProject());
        assertThat(g.classes()).extracting(ClassNode::fqn)
                .contains("com.x.PubsubService", "com.x.PubsubServiceImpl", "com.x.OrderService");
    }

    @Test
    void capturesInterfaceKindAndImplementsEdge() {
        SemanticGraph g = graphOf(sampleProject());

        ClassNode iface = g.classes().stream()
                .filter(c -> c.fqn().equals("com.x.PubsubService")).findFirst().orElseThrow();
        assertThat(iface.kind()).isEqualTo(BlueprintFile.Kind.INTERFACE);

        // PubsubServiceImpl implements PubsubService.
        assertThat(g.inheritance()).anyMatch(e ->
                e.subtypeFqn().equals("com.x.PubsubServiceImpl")
                        && e.supertypeFqn().equals("com.x.PubsubService"));
    }

    @Test
    void capturesProjectMethodCall() {
        SemanticGraph g = graphOf(sampleProject());

        // OrderService.run() calls PubsubService.publish(String,String).
        assertThat(g.calls()).anyMatch(c ->
                c.callerFqn().equals("com.x.OrderService")
                        && c.calleeKey().startsWith("com.x.PubsubService#publish")
                        && c.projectOwned());

        // The convenience view resolves callers of the interface.
        assertThat(g.callersOf("com.x.PubsubService")).contains("OrderService");
    }

    @Test
    void indexesMethodSignatures() {
        SemanticGraph g = graphOf(sampleProject());
        assertThat(g.methodsOf("com.x.PubsubService"))
                .anyMatch(m -> m.name().equals("publish") && m.parameterTypes().size() == 2);
    }

    @Test
    void recordsImports() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("src/main/java/com/x/A.java", """
                package com.x;
                import java.util.List;
                public class A { List<String> items; }
                """);
        SemanticGraph g = graphOf(p);
        assertThat(g.imports()).anyMatch(e ->
                e.importerFqn().equals("com.x.A") && e.targetFqn().equals("java.util.List"));
    }

    @Test
    void externalCallIsNotProjectOwned() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("src/main/java/com/x/A.java", """
                package com.x;
                public class A {
                    void go() { String s = "x"; s.length(); }
                }
                """);
        SemanticGraph g = graphOf(p);
        // s.length() lands on java.lang.String — external, not project-owned.
        assertThat(g.calls()).noneMatch(c ->
                c.calleeKey().startsWith("com.x.") && c.projectOwned()
                        && c.calleeKey().contains("length"));
    }
}
