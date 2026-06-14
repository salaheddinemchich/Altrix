package com.altrix.orchestrator.domain.model.blueprint;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectBlueprintTest {

    @Test
    void rejectsBlankIdentifiers() {
        assertThatThrownBy(() -> new ProjectBlueprint(
                " ", "sess", null, 0, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");

        assertThatThrownBy(() -> new ProjectBlueprint(
                "proj", null, null, 0, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    void normalisesNullCollectionsToEmptyImmutableLists() {
        ProjectBlueprint b = new ProjectBlueprint(
                "proj", "sess", null, 0,
                null, null, null, null, null, null, null);

        assertThat(b.detectedStack()).isEqualTo(DetectedStack.unknown());
        assertThat(b.detectedIntegrations()).isEmpty();
        assertThat(b.files()).isEmpty();
        assertThat(b.semanticGraph()).isEqualTo(SemanticGraph.empty());
        assertThat(b.docReferences()).isEmpty();
        assertThat(b.migrationOrder()).isEmpty();
        assertThat(b.riskNotes()).isEmpty();
        // generatedAt defaulted to "now-ish".
        assertThat(b.generatedAt()).isCloseTo(Instant.now(),
                org.assertj.core.api.Assertions.within(2, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(b.schemaVersion()).isEqualTo(ProjectBlueprint.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void fileSliceFindsByExactPath() {
        BlueprintFile a = file("src/main/java/A.java", "A");
        BlueprintFile b = file("src/main/java/B.java", "B");
        ProjectBlueprint bp = blueprintWith(List.of(a, b));

        assertThat(bp.fileSlice("src/main/java/B.java")).contains(b);
        assertThat(bp.fileSlice("does/not/exist.java")).isEmpty();
        assertThat(bp.fileSlice(null)).isEmpty();
    }

    @Test
    void migrationRelevantFileCountIgnoresPassThroughFiles() {
        BlueprintFile a = fileWithFeature("A.java", "A", "pubsub.publish");
        BlueprintFile b = file("B.java", "B");  // no features → pass-through
        BlueprintFile c = fileWithFeature("C.java", "C", "pubsub.pull-ack");
        ProjectBlueprint bp = blueprintWith(List.of(a, b, c));

        assertThat(bp.migrationRelevantFileCount()).isEqualTo(2);
        assertThat(a.isPassThrough()).isFalse();
        assertThat(b.isPassThrough()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static BlueprintFile file(String path, String simpleName) {
        return new BlueprintFile(path, simpleName, BlueprintFile.Kind.CLASS, "x",
                "role", BlueprintRelationships.empty(), List.of(), null);
    }

    private static BlueprintFile fileWithFeature(String path, String simpleName, String featureId) {
        BlueprintFeature feature = new BlueprintFeature(
                featureId, "desc",
                new BlueprintFeature.Evidence("m", 1, "snippet"),
                "kafka-target", List.of("kafka/producers"));
        return new BlueprintFile(path, simpleName, BlueprintFile.Kind.CLASS, "x",
                "role", BlueprintRelationships.empty(), List.of(feature), null);
    }

    private static ProjectBlueprint blueprintWith(List<BlueprintFile> files) {
        return new ProjectBlueprint("proj", "sess", null, 0,
                null, null, files, null, null, null, null);
    }
}
