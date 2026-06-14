package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationOrderResolverTest {

    private final MigrationOrderResolver resolver = new MigrationOrderResolver();

    private ClassNode cls(String fqn, String file) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return new ClassNode(fqn, simple, "com.x", BlueprintFile.Kind.CLASS, file, true, List.of(), List.of());
    }

    @Test
    void emptyGraphYieldsEmptyOrder() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve(SemanticGraph.empty())).isEmpty();
    }

    @Test
    void leafBeforeDependent() {
        // OrderService depends on PubsubService (calls it). Leaf = PubsubService.
        var graph = new SemanticGraph(
                List.of(cls("com.x.PubsubService", "P.java"), cls("com.x.OrderService", "O.java")),
                List.of(),
                List.of(),
                List.of(new CallEdge("com.x.OrderService", 1, "com.x.PubsubService#publish/2", true)),
                List.of());

        List<String> order = resolver.resolve(graph);
        assertThat(order).containsExactly("P.java", "O.java");   // leaf first
        assertThat(resolver.hasCycle(graph)).isFalse();
    }

    @Test
    void inheritanceOrdersSupertypeFirst() {
        var graph = new SemanticGraph(
                List.of(cls("com.x.Base", "Base.java"), cls("com.x.Impl", "Impl.java")),
                List.of(),
                List.of(new InheritanceEdge("com.x.Impl", "com.x.Base", InheritanceEdge.Kind.IMPLEMENTS)),
                List.of(),
                List.of());
        assertThat(resolver.resolve(graph)).containsExactly("Base.java", "Impl.java");
    }

    @Test
    void cyclicClassesAreAppendedLastAndFlagged() {
        // A calls B, B calls A — a cycle.
        var graph = new SemanticGraph(
                List.of(cls("com.x.A", "A.java"), cls("com.x.B", "B.java"), cls("com.x.Leaf", "Leaf.java")),
                List.of(),
                List.of(),
                List.of(
                        new CallEdge("com.x.A", 1, "com.x.B#m/0", true),
                        new CallEdge("com.x.B", 1, "com.x.A#m/0", true),
                        new CallEdge("com.x.A", 2, "com.x.Leaf#m/0", true)),
                List.of());

        assertThat(resolver.hasCycle(graph)).isTrue();
        List<String> order = resolver.resolve(graph);
        // Leaf (no project deps) comes before the cyclic A/B.
        assertThat(order).containsExactlyInAnyOrder("Leaf.java", "A.java", "B.java");
        assertThat(order.indexOf("Leaf.java")).isLessThan(order.indexOf("A.java"));
    }

    @Test
    void externalCallsDoNotAffectOrder() {
        var graph = new SemanticGraph(
                List.of(cls("com.x.A", "A.java")),
                List.of(),
                List.of(),
                // External call — not project-owned, must be ignored.
                List.of(new CallEdge("com.x.A", 1, "java.lang.String#length/0", false)),
                List.of());
        assertThat(resolver.resolve(graph)).containsExactly("A.java");
        assertThat(resolver.hasCycle(graph)).isFalse();
    }
}
