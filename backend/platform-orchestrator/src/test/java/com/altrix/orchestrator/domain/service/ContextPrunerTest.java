package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.infrastructure.ai.ContextPruner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPrunerTest {

    private ContextPruner pruner;

    @BeforeEach
    void setUp() {
        // Default budget of 32 000 tokens
        pruner = new ContextPruner(32_000);
    }

    @Test
    void prune_nullFiles_returnsEmpty() {
        PrunedContext result = pruner.prune(null, MigrationPlan.empty("p1"));
        assertThat(result.files()).isEmpty();
        assertThat(result.totalFiles()).isZero();
        assertThat(result.prunedFiles()).isZero();
    }

    @Test
    void prune_emptyFiles_returnsEmpty() {
        PrunedContext result = pruner.prune(Map.of(), MigrationPlan.empty("p1"));
        assertThat(result.files()).isEmpty();
    }

    @Test
    void prune_planHasNoTargetFiles_returnsAllFiles() {
        Map<String, String> files = Map.of(
                "A.java", "class A {}",
                "B.java", "class B {}");
        MigrationPlan plan = planWithTargets("p1", List.of());

        PrunedContext result = pruner.prune(files, plan);

        assertThat(result.files()).containsKeys("A.java", "B.java");
        assertThat(result.totalFiles()).isEqualTo(2);
        assertThat(result.prunedFiles()).isZero();
    }

    @Test
    void prune_planTargetFiles_keepsOnlyTargetFiles() {
        Map<String, String> allFiles = new HashMap<>();
        for (int i = 1; i <= 100; i++) {
            allFiles.put("File" + i + ".java", "class File" + i + " {}");
        }
        List<String> targets = List.of("File3.java", "File7.java", "File42.java");
        MigrationPlan plan = planWithTargets("p1", targets);

        PrunedContext result = pruner.prune(allFiles, plan);

        assertThat(result.files()).containsOnlyKeys("File3.java", "File7.java", "File42.java");
        assertThat(result.totalFiles()).isEqualTo(100);
        assertThat(result.prunedFiles()).isEqualTo(97);
        assertThat(result.includedFiles()).isEqualTo(3);
    }

    @Test
    void prune_targetFileNotInAllFiles_skipsGracefully() {
        Map<String, String> allFiles = Map.of("Real.java", "class Real {}");
        MigrationPlan plan = planWithTargets("p1", List.of("Ghost.java", "Real.java"));

        PrunedContext result = pruner.prune(allFiles, plan);

        assertThat(result.files()).containsOnlyKeys("Real.java");
        assertThat(result.prunedFiles()).isZero();
    }

    @Test
    void prune_nullPlan_returnsAllFiles() {
        Map<String, String> files = Map.of("A.java", "class A {}");
        PrunedContext result = pruner.prune(files, null);
        assertThat(result.files()).containsKey("A.java");
    }

    @Test
    void pruneRatio_correctlyComputed() {
        Map<String, String> allFiles = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            allFiles.put("F" + i + ".java", "class F" + i + " {}");
        }
        MigrationPlan plan = planWithTargets("p1", List.of("F1.java", "F2.java"));
        PrunedContext result = pruner.prune(allFiles, plan);

        assertThat(result.pruneRatio()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void tokenBudget_truncatesWhenExceeded() {
        // Use a very tight budget pruner
        ContextPruner tightPruner = new ContextPruner(10);

        // Each file is ~200 chars → well over 10-token budget
        Map<String, String> files = IntStream.rangeClosed(1, 5)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "File" + i + ".java",
                        i -> "class File" + i + " { void method() { /* lots of content here */ } }"
                ));
        MigrationPlan plan = planWithTargets("p1", List.of());

        PrunedContext result = tightPruner.prune(files, plan);

        // Should have retained fewer files than the full set
        assertThat(result.includedFiles()).isLessThan(5);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MigrationPlan planWithTargets(String projectId, List<String> targets) {
        return new MigrationPlan(projectId, "key", "Spring Boot 3 + Kafka",
                List.of(), "MEDIUM", "TBD", "summary", targets);
    }
}
