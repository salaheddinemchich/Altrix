package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the runner contract: it returns structured {@link SandboxFinding}s,
 * never throws, and is deterministic.  The agent-level assertions (the legacy
 * "ValidationReport.failures must contain ..." shape) live in
 * {@code SandboxValidatorAgentTest}.
 */
class StaticSandboxRunnerTest {

    private final StaticSandboxRunner runner = new StaticSandboxRunner();

    @Test
    void exposesStableIdAndOrder() {
        assertThat(runner.id()).isEqualTo("static");
        assertThat(runner.order()).isEqualTo(0);
        assertThat(runner.isAvailable()).isTrue();
    }

    @Test
    void emptyArtifact_returnsNoFindings() {
        assertThat(runner.run(MigrationArtifact.empty("p1"))).isEmpty();
    }

    @Test
    void nullArtifact_returnsEmptyList_neverThrows() {
        // SandboxRunnerPort contract: must not throw.
        assertThat(runner.run(null)).isEmpty();
    }

    @Test
    void modifiedFileWithResidualModernImport_flaggedAsError() {
        MigratedFile f = MigratedFile.builder()
                .originalPath("Listener.java").newPath("Listener.java")
                .content("import google.cloud.pubsub; class Listener {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("partial")
                .build();
        List<SandboxFinding> findings = runner.run(new MigrationArtifact("p1", List.of(f), "done", null));
        assertThat(findings).anySatisfy(s -> {
            assertThat(s.severity()).isEqualTo(Severity.ERROR);
            assertThat(s.runnerId()).isEqualTo("static");
            assertThat(s.filePath()).isEqualTo("Listener.java");
            assertThat(s.message()).contains("Pub/Sub import not removed");
        });
    }

    @Test
    void modifiedJavaFileWithResidualLegacyImport_flaggedAsError() {
        MigratedFile f = MigratedFile.builder()
                .originalPath("Svc.java").newPath("Svc.java")
                .content("import com.google.api.services.pubsub.Pubsub; class Svc {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("partial")
                .build();
        assertThat(runner.run(new MigrationArtifact("p1", List.of(f), "done", null)))
                .anyMatch(s -> s.severity() == Severity.ERROR
                            && s.message().contains("Legacy GCP Pub/Sub REST v1 import"));
    }

    @Test
    void unchangedFile_doesNotTriggerPerFileChecks() {
        MigratedFile f = MigratedFile.builder()
                .originalPath("Util.java").newPath("Util.java")
                .content("import google.cloud.pubsub; class Util {}")
                .changeType(FileChangeType.UNCHANGED).diffSummary("not migrated")
                .build();
        List<SandboxFinding> findings = runner.run(new MigrationArtifact("p1", List.of(f), "done", null));
        // No per-file error; only the project-level "nothing migrated" WARNING
        // since the artifact has files but none MODIFIED.
        assertThat(findings).allMatch(s -> s.severity() != Severity.ERROR);
    }
}
