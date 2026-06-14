package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.infrastructure.migration.ReferenceComparisonScorer.MigrationQualityReport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceComparisonScorerTest {

    // ── pure unit tests (CI-safe) ───────────────────────────────────────────

    @Test
    void perfectKafkaOutputScoresHigh() {
        Map<String, String> ref = Map.of(
                "m/KafkaMessagingService.java", "import org.apache.kafka.clients.producer.KafkaProducer; class A {}");
        Map<String, String> migrated = Map.of(
                "m/KafkaMessagingService.java", "import org.apache.kafka.clients.producer.KafkaProducer; class A {}");
        MigrationQualityReport r = ReferenceComparisonScorer.score(migrated, ref);
        assertThat(r.pubsubResidueFiles()).isEmpty();
        assertThat(r.adoptionScore()).isEqualTo(1.0);
        assertThat(r.structuralScore()).isEqualTo(1.0);
        assertThat(r.overallScore()).isGreaterThan(90.0);
    }

    @Test
    void pubsubResidueAndBloatScoreLow() {
        Map<String, String> ref = Map.of("m/KafkaMessagingService.java", "org.apache.kafka.clients X");
        // Migration kept Pub/Sub residue AND twice as many files (no consolidation).
        Map<String, String> migrated = new LinkedHashMap<>();
        migrated.put("p/PubsubServiceImpl.java", "import com.google.api.services.pubsub.Pubsub; class A {}");
        migrated.put("p/RetryTask.java", "class RetryTask {}");
        MigrationQualityReport r = ReferenceComparisonScorer.score(migrated, ref);
        assertThat(r.pubsubResidueFiles()).hasSize(1);
        assertThat(r.extraFiles()).contains("PubsubServiceImpl.java", "RetryTask.java");
        assertThat(r.overallScore()).isLessThan(50.0);
    }

    @Test
    void reportsMissingReferenceFiles() {
        Map<String, String> ref = Map.of(
                "m/KafkaMessagingService.java", "org.apache.kafka X",
                "m/KafkaConfig.java", "org.apache.kafka Y");
        Map<String, String> migrated = Map.of("m/KafkaMessagingService.java", "org.apache.kafka X");
        MigrationQualityReport r = ReferenceComparisonScorer.score(migrated, ref);
        assertThat(r.missingFiles()).containsExactly("KafkaConfig.java");
    }

    // ── golden harness vs the real reference (run manually) ─────────────────

    /**
     * Scores a real migration output against the hand-migrated reference.
     * Provide directories via system properties:
     * <pre>
     * gradlew :platform-orchestrator:test --tests "*ReferenceComparisonScorerTest.goldenHarness" \
     *   -Daltrix.eval.migrated=C:/Users/SALAH/Projects/Altrix/backend/logs/&lt;jobId&gt;/m \
     *   -Daltrix.eval.reference=C:/Users/SALAH/Projects/test-altrix-kafka
     * </pre>
     * Skipped when the properties / paths aren't set, so CI stays green.
     */
    @Test
    void goldenHarness() throws IOException {
        String migDir = System.getProperty("altrix.eval.migrated");
        String refDir = System.getProperty("altrix.eval.reference",
                "C:/Users/SALAH/Projects/test-altrix-kafka");
        Assumptions.assumeTrue(migDir != null && Files.isDirectory(Path.of(migDir)),
                "set -Daltrix.eval.migrated=<dir> to run the golden harness");
        Assumptions.assumeTrue(Files.isDirectory(Path.of(refDir)),
                "reference project not found at " + refDir);

        MigrationQualityReport r = ReferenceComparisonScorer.score(
                readJavaTree(Path.of(migDir)), readJavaTree(Path.of(refDir)));
        System.out.println(r.render());
        assertThat(r.overallScore()).isBetween(0.0, 100.0);
    }

    private static Map<String, String> readJavaTree(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            paths.filter(p -> p.toString().toLowerCase().endsWith(".java"))
                 .forEach(p -> {
                     try {
                         out.put(root.relativize(p).toString().replace('\\', '/'),
                                 Files.readString(p, StandardCharsets.UTF_8));
                     } catch (IOException ignored) { }
                 });
        }
        return out;
    }
}
