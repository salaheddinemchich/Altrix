package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.adapter.out.sandbox.StaticSandboxRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxValidatorAgentTest {

    // Compose with the real static runner so existing behaviour assertions
    // (the bulk of this suite is "static checks still catch X") continue to
    // hold.  When more runners land they'll get their own dedicated suite.
    private final SandboxValidatorAgent agent =
            new SandboxValidatorAgent(List.of(new StaticSandboxRunner()));

    @Test
    void exposesNameAndOrder4() {
        assertThat(agent.getName()).isEqualTo("Sandbox Validator");
        assertThat(agent.getOrder()).isEqualTo(4);
    }

    @Test
    void execute_emptyArtifact_passes() {
        ValidationReport report = agent.execute(MigrationArtifact.empty("p1"));
        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isEmpty();
        assertThat(report.summary()).contains("all static checks passed");
    }

    @Test
    void execute_cleanMigratedFile_passes() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("Listener.java").newPath("Listener.java")
                .content("import org.springframework.kafka.annotation.KafkaListener; class Listener {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("migrated")
                .build();
        ValidationReport report = agent.execute(new MigrationArtifact("p1", List.of(file), "done", null));
        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void execute_residualPubSubImport_fails() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("Listener.java").newPath("Listener.java")
                .content("import google.cloud.pubsub; class Listener {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("partial migration")
                .build();
        ValidationReport report = agent.execute(new MigrationArtifact("p1", List.of(file), "done", null));
        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("Pub/Sub import not removed"));
    }

    @Test
    void execute_residualPubSubAnnotation_fails() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("Sub.java").newPath("Sub.java")
                .content("@SubscriberHandler class Sub {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("partial migration")
                .build();
        ValidationReport report = agent.execute(new MigrationArtifact("p1", List.of(file), "done", null));
        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("annotation still present"));
    }

    @Test
    void execute_unchangedFiles_skippedFromValidation() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("Util.java").newPath("Util.java")
                .content("import google.cloud.pubsub; class Util {}")
                .changeType(FileChangeType.UNCHANGED).diffSummary("not migrated")
                .build();
        ValidationReport report = agent.execute(new MigrationArtifact("p1", List.of(file), "done", null));
        // UNCHANGED files are not subject to migration-correctness checks
        assertThat(report.passed()).isTrue();
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }

    // #94 — structured findings exposed alongside the legacy failures list.
    @Test
    void execute_residualPubSubImport_populatesStructuredFindings() {
        MigratedFile file = MigratedFile.builder()
                .originalPath("Listener.java").newPath("Listener.java")
                .content("import google.cloud.pubsub; class Listener {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("partial migration")
                .build();
        ValidationReport report = agent.execute(new MigrationArtifact("p1", List.of(file), "done", null));

        // Back-compat surface still works
        assertThat(report.failures()).isNotEmpty();
        // New structured surface carries the same data with runner + severity + path
        assertThat(report.findings())
                .isNotEmpty()
                .anySatisfy(f -> {
                    assertThat(f.runnerId()).isEqualTo("static");
                    assertThat(f.severity()).isEqualTo("ERROR");
                    assertThat(f.filePath()).isEqualTo("Listener.java");
                    assertThat(f.message()).contains("Pub/Sub import not removed");
                });
    }
}
