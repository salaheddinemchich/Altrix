package com.altrix.orchestrator.domain.model.blueprint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticGraphTest {

    @Test
    void emptyHasNoNodesNoEdges() {
        SemanticGraph g = SemanticGraph.empty();
        assertThat(g.classes()).isEmpty();
        assertThat(g.methods()).isEmpty();
        assertThat(g.inheritance()).isEmpty();
        assertThat(g.calls()).isEmpty();
        assertThat(g.imports()).isEmpty();
    }

    @Test
    void methodsOfReturnsOnlyTheRequestedOwner() {
        MethodNode a1 = new MethodNode("com.x.A", "foo", List.of(), "void", List.of());
        MethodNode a2 = new MethodNode("com.x.A", "bar", List.of("java.lang.String"), "int", List.of());
        MethodNode b1 = new MethodNode("com.x.B", "baz", List.of(), "void", List.of());
        SemanticGraph g = new SemanticGraph(List.of(), List.of(a1, a2, b1),
                List.of(), List.of(), List.of());

        assertThat(g.methodsOf("com.x.A")).containsExactly(a1, a2);
        assertThat(g.methodsOf("com.x.B")).containsExactly(b1);
        assertThat(g.methodsOf("com.x.Nope")).isEmpty();
    }

    @Test
    void callersOfReturnsSimpleNamesOfProjectCallers() {
        // OrderService.run() calls PubsubService.publish/2
        CallEdge call1 = new CallEdge("com.x.OrderService", 12, "com.x.PubsubService#publish/2", true);
        // PaymentService.run() also calls PubsubService.publish/2
        CallEdge call2 = new CallEdge("com.x.PaymentService", 45, "com.x.PubsubService#publish/2", true);
        // Helper.run() calls something different — must NOT be returned
        CallEdge call3 = new CallEdge("com.x.Helper", 7, "com.x.Other#bar/0", true);

        SemanticGraph g = new SemanticGraph(List.of(), List.of(),
                List.of(), List.of(call1, call2, call3), List.of());

        assertThat(g.callersOf("com.x.PubsubService")).containsExactlyInAnyOrder("OrderService", "PaymentService");
        assertThat(g.callersOf("com.x.NotCalled")).isEmpty();
    }

    @Test
    void calleesOfReturnsResolvedOwnersOnly() {
        CallEdge inProject = new CallEdge("com.x.OrderService", 12, "com.x.PubsubService#publish/2", true);
        CallEdge external  = new CallEdge("com.x.OrderService", 13, "java.lang.String#valueOf/1", false);
        SemanticGraph g = new SemanticGraph(List.of(), List.of(),
                List.of(), List.of(inProject, external), List.of());

        // calleesOf reports only the project-owned target.
        assertThat(g.calleesOf("com.x.OrderService")).containsExactly("com.x.PubsubService");
    }

    @Test
    void importersOfReturnsEveryFileImportingTarget() {
        ImportEdge i1 = new ImportEdge("com.x.OrderService", "com.google.api.services.pubsub.Pubsub", false);
        ImportEdge i2 = new ImportEdge("com.x.PaymentService", "com.google.api.services.pubsub.Pubsub", false);
        ImportEdge i3 = new ImportEdge("com.x.Other", "java.util.List", false);
        SemanticGraph g = new SemanticGraph(List.of(), List.of(),
                List.of(), List.of(), List.of(i1, i2, i3));

        assertThat(g.importersOf("com.google.api.services.pubsub.Pubsub"))
                .containsExactlyInAnyOrder("com.x.OrderService", "com.x.PaymentService");
    }

    @Test
    void classesByFileGroupsCorrectly() {
        ClassNode a = new ClassNode("com.x.A", "A", "com.x", BlueprintFile.Kind.CLASS,
                "src/A.java", true, List.of(), List.of());
        ClassNode b = new ClassNode("com.x.B", "B", "com.x", BlueprintFile.Kind.CLASS,
                "src/A.java", true, List.of(), List.of());
        ClassNode c = new ClassNode("com.x.C", "C", "com.x", BlueprintFile.Kind.CLASS,
                "src/C.java", true, List.of(), List.of());
        SemanticGraph g = new SemanticGraph(List.of(a, b, c), List.of(),
                List.of(), List.of(), List.of());

        assertThat(g.classesByFile()).containsOnlyKeys("src/A.java", "src/C.java");
        assertThat(g.classesByFile().get("src/A.java")).containsExactly(a, b);
        assertThat(g.classesByFile().get("src/C.java")).containsExactly(c);
    }
}
