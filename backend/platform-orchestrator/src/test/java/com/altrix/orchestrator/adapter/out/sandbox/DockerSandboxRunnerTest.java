package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.infrastructure.config.SandboxDockerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — never touches a real Docker daemon.  Exercise the
 * opt-in flag, the no-pom early return, and the Maven-error parser.
 * The actual docker-java client path is covered by the integration test
 * (gated on Docker availability).
 */
class DockerSandboxRunnerTest {

    @Test
    void idAndOrder_areStable() {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        assertThat(r.id()).isEqualTo("docker");
        assertThat(r.order()).isEqualTo(10);   // after static (0) + migration-quality (1)
    }

    @Test
    void disabledByDefault_isAvailableReturnsFalse() {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        // probeDaemon hasn't run (no @PostConstruct outside of Spring); the
        // enabled flag alone gates isAvailable false.
        assertThat(r.isAvailable()).isFalse();
    }

    @Test
    void noPomInArtifact_returnsInfoFinding_doesNotTouchDocker() {
        // enabled+daemon-not-probed → daemonReachable=false → isAvailable=false.
        // But run() is still safe to call directly; with no pom.xml the
        // early-return path executes before any docker call.
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());

        MigratedFile java = MigratedFile.builder()
                .originalPath("Foo.java").newPath("Foo.java")
                .content("class Foo {}")
                .changeType(FileChangeType.MODIFIED).diffSummary("rewrite")
                .build();
        MigrationArtifact art = new MigrationArtifact("p1", List.of(java), "done");

        List<SandboxFinding> findings = r.run(art);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(SandboxFinding.Severity.INFO);
        assertThat(findings.get(0).message()).contains("No pom.xml");
    }

    @Test
    void emptyArtifact_returnsEmptyFindings() {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        assertThat(r.run(MigrationArtifact.empty("p1"))).isEmpty();
    }

    @Test
    void nullArtifact_returnsEmptyFindings_neverThrows() {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        assertThat(r.run(null)).isEmpty();
    }

    // ── Maven error parsing ──────────────────────────────────────────────────

    @Test
    void mavenErrorParser_extractsPathLineAndMessage() throws Exception {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        String logs = """
                [INFO] Scanning for projects...
                [INFO] Compiling 3 source files
                [ERROR] /workspace/src/main/java/com/example/Listener.java:[12,5] cannot find symbol
                [ERROR]   symbol: class Kafkalistener
                [ERROR] /workspace/src/main/java/com/example/Other.java:[7,1] package org.foo does not exist
                [ERROR] BUILD FAILURE
                """;
        List<SandboxFinding> findings = invokeInterpret(r, 1, logs);

        // Should pick up the two structured rows; the BUILD FAILURE line
        // doesn't match the per-line regex and is dropped.
        assertThat(findings).hasSize(2);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.filePath()).isEqualTo("src/main/java/com/example/Listener.java");
            assertThat(f.line()).isEqualTo(12);
            assertThat(f.message()).contains("cannot find symbol");
            assertThat(f.severity()).isEqualTo(SandboxFinding.Severity.ERROR);
        });
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.filePath()).isEqualTo("src/main/java/com/example/Other.java");
            assertThat(f.line()).isEqualTo(7);
            assertThat(f.message()).contains("package org.foo does not exist");
        });
    }

    @Test
    void mavenSuccess_returnsSingleInfoFinding() throws Exception {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        List<SandboxFinding> findings = invokeInterpret(r, 0, "[INFO] BUILD SUCCESS\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(SandboxFinding.Severity.INFO);
        assertThat(findings.get(0).message()).contains("Sandbox compile passed");
    }

    @Test
    void mavenFailureWithoutParseableLines_emitsErrorWithLogTail() throws Exception {
        DockerSandboxRunner r = new DockerSandboxRunner(disabledConfig());
        String logs = "[ERROR] Something went wrong in plugin\n[ERROR] BUILD FAILURE\n";
        List<SandboxFinding> findings = invokeInterpret(r, 1, logs);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(SandboxFinding.Severity.ERROR);
        assertThat(findings.get(0).message()).contains("Sandbox compile failed");
        assertThat(findings.get(0).message()).contains("BUILD FAILURE");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SandboxDockerConfig disabledConfig() {
        return new SandboxDockerConfig(false, "maven:3.9-eclipse-temurin-21-alpine",
                Duration.ofMinutes(5), 1024, "2.0", "bridge", "altrix-sandbox-",
                new SandboxDockerConfig.Reaper(true, Duration.ofMinutes(10), Duration.ofMinutes(30)));
    }

    /** Invoke the private {@code interpret} for unit-level coverage. */
    @SuppressWarnings("unchecked")
    private static List<SandboxFinding> invokeInterpret(DockerSandboxRunner r, int exitCode, String logs)
            throws Exception {
        var method = DockerSandboxRunner.class.getDeclaredMethod("interpret", int.class, String.class);
        method.setAccessible(true);
        return (List<SandboxFinding>) method.invoke(r, exitCode, logs);
    }
}
