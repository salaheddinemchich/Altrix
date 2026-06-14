package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.domain.model.blueprint.ClassNode;
import com.altrix.orchestrator.domain.model.blueprint.InheritanceEdge;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationClusterPlannerTest {

    private final MigrationClusterPlanner planner = new MigrationClusterPlanner(6);

    private ClassNode cls(String fqn, String file, boolean owned) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return new ClassNode(fqn, simple, "p", BlueprintFile.Kind.CLASS, file, owned, List.of(), List.of());
    }

    private ProjectBlueprint blueprint(SemanticGraph g) {
        return new ProjectBlueprint("proj", "sess", Instant.now(), 1, null,
                List.of(), List.of(), g, List.of(), List.of(), List.of());
    }

    @Test
    void clustersInterfaceWithItsImplementor() {
        SemanticGraph g = new SemanticGraph(
                List.of(cls("p.PubsubService", "p/PubsubService.java", true),
                        cls("p.PubsubServiceImpl", "p/PubsubServiceImpl.java", true)),
                List.of(),
                List.of(new InheritanceEdge("p.PubsubServiceImpl", "p.PubsubService",
                        InheritanceEdge.Kind.IMPLEMENTS)),
                List.of(), List.of());

        List<List<String>> clusters = planner.plan(blueprint(g));
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0)).containsExactlyInAnyOrder(
                "p/PubsubService.java", "p/PubsubServiceImpl.java");
    }

    @Test
    void doesNotClusterAcrossExternalSupertypes() {
        // Implementing an EXTERNAL interface (not project-owned) must not cluster.
        SemanticGraph g = new SemanticGraph(
                List.of(cls("p.MyResource", "p/MyResource.java", true)),
                List.of(),
                List.of(new InheritanceEdge("p.MyResource", "jakarta.ws.rs.core.Application",
                        InheritanceEdge.Kind.EXTENDS)),
                List.of(), List.of());
        assertThat(planner.plan(blueprint(g))).isEmpty();
    }

    @Test
    void dropsClustersLargerThanMax() {
        var small = new MigrationClusterPlanner(2);
        // base + 2 impls = 3 files > max 2 → dropped.
        SemanticGraph g = new SemanticGraph(
                List.of(cls("p.Base", "p/Base.java", true),
                        cls("p.A", "p/A.java", true),
                        cls("p.B", "p/B.java", true)),
                List.of(),
                List.of(new InheritanceEdge("p.A", "p.Base", InheritanceEdge.Kind.EXTENDS),
                        new InheritanceEdge("p.B", "p.Base", InheritanceEdge.Kind.EXTENDS)),
                List.of(), List.of());
        assertThat(small.plan(blueprint(g))).isEmpty();
    }

    @Test
    void emptyWhenNoInheritance() {
        SemanticGraph g = new SemanticGraph(
                List.of(cls("p.Standalone", "p/Standalone.java", true)),
                List.of(), List.of(), List.of(), List.of());
        assertThat(planner.plan(blueprint(g))).isEmpty();
    }

    // ── response parsing ────────────────────────────────────────────────────

    @Test
    void parsesMultiFileResponse() {
        String resp = """
                Here are the migrated files:
                === FILE: p/PubsubService.java ===
                package p;
                public interface PubsubService { void publish(String t, byte[] m); }
                === FILE: p/PubsubServiceImpl.java ===
                package p;
                public class PubsubServiceImpl implements PubsubService {
                    public void publish(String t, byte[] m) {}
                }
                """;
        Map<String, String> files = MigrationClusterPlanner.parseResponse(resp);
        assertThat(files).containsOnlyKeys("p/PubsubService.java", "p/PubsubServiceImpl.java");
        assertThat(files.get("p/PubsubService.java")).startsWith("package p;").contains("interface PubsubService");
        assertThat(files.get("p/PubsubServiceImpl.java")).contains("implements PubsubService");
    }

    @Test
    void parseIgnoresPreambleAndBlankResponse() {
        assertThat(MigrationClusterPlanner.parseResponse("")).isEmpty();
        assertThat(MigrationClusterPlanner.parseResponse("no markers here")).isEmpty();
    }
}
