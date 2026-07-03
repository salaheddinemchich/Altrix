package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationQualityRunnerTest {

    private final MigrationQualityRunner runner = new MigrationQualityRunner();

    @Test
    void idAndOrder_areStable() {
        assertThat(runner.id()).isEqualTo("migration-quality");
        assertThat(runner.order()).isEqualTo(1);   // after StaticSandboxRunner(0)
    }

    @Test
    void nullArtifact_returnsEmpty_neverThrows() {
        assertThat(runner.run(null)).isEmpty();
    }

    @Test
    void unchangedFile_isSkipped() {
        MigratedFile f = file("X.java", FileChangeType.UNCHANGED,
                "// TODO: implement\nvoid x() {}");
        assertThat(runner.run(art(f))).isEmpty();
    }

    @Test
    void nonJavaFile_isSkipped() {
        MigratedFile f = file("pom.xml", FileChangeType.MODIFIED,
                "<!-- TODO: add kafka dep -->");
        assertThat(runner.run(art(f))).isEmpty();
    }

    @Test
    void aiTodoMarker_flaggedAsError() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "class S { void on() { // TODO: implement Kafka consumer\n} }");
        assertHasError(runner.run(art(f)), "AI scaffolding marker");
    }

    @Test
    void aiPlaceholderMarker_flaggedAsError() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "class S { void on() { /* Add your business logic here */ } }");
        assertHasError(runner.run(art(f)), "AI scaffolding marker");
    }

    @Test
    void emptyMethodBody_flaggedAsError() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "class S { public void onMessage(String s) { } }");
        assertHasError(runner.run(art(f)), "Empty method body");
    }

    @Test
    void pubsubExceptionInCatch_flaggedAsError() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "class S { void on() { try { x(); } catch (PubSubException e) { } } }");
        assertHasError(runner.run(art(f)), "PubSubException");
    }

    @Test
    void pubsubExceptionInThrows_flaggedAsError() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "class S { void on() throws PubsubException { kafkaCall(); } }");
        assertHasError(runner.run(art(f)), "PubSubException");
    }

    @Test
    void pubsubLogReference_flaggedAsWarning_notError() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "class S { void on() { log.info(\"Publishing to Pub/Sub: \" + t); } }");
        List<SandboxFinding> findings = runner.run(art(f));
        assertThat(findings).anySatisfy(s -> {
            assertThat(s.severity()).isEqualTo(Severity.WARNING);
            assertThat(s.message()).contains("Log message still mentions Pub/Sub");
        });
        assertThat(findings).noneMatch(s -> s.severity() == Severity.ERROR);
    }

    @Test
    void cleanMigratedJava_returnsNoFindings() {
        MigratedFile f = file("Sub.java", FileChangeType.MODIFIED,
                "import org.springframework.kafka.annotation.KafkaListener;\n" +
                "class S {\n" +
                "  @KafkaListener(topics = \"orders\")\n" +
                "  public void on(ConsumerRecord<String,String> r) {\n" +
                "    log.info(\"received {}\", r.value());\n" +
                "    process(r.value());\n" +
                "  }\n" +
                "}");
        assertThat(runner.run(art(f))).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MigratedFile file(String path, FileChangeType type, String content) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path)
                .content(content)
                .changeType(type).diffSummary("test")
                .build();
    }

    private static MigrationArtifact art(MigratedFile f) {
        return new MigrationArtifact("p1", List.of(f), "done", null);
    }

    private static void assertHasError(List<SandboxFinding> findings, String messageSubstring) {
        assertThat(findings)
                .as("expected ERROR with message containing '" + messageSubstring + "'")
                .anySatisfy(s -> {
                    assertThat(s.severity()).isEqualTo(Severity.ERROR);
                    assertThat(s.runnerId()).isEqualTo("migration-quality");
                    assertThat(s.message()).contains(messageSubstring);
                });
    }
}
