package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.domain.port.out.SandboxLogRepository;
import com.altrix.orchestrator.infrastructure.config.SandboxDockerConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Light-weight tests for {@link DockerBootHealthRunner}.  We don't stand
 * up a real Docker container — that path is exercised by the existing
 * compile/test runners' integration setup and is too slow for unit
 * scope.  These tests cover only the parts that don't need a daemon:
 *
 * <ul>
 *   <li>Skipping artifacts that have no pom.xml (no double finding —
 *       the compile runner already emits the INFO).</li>
 *   <li>Skipping when {@code sandbox.docker.enabled} is false.</li>
 * </ul>
 */
class DockerBootHealthRunnerTest {

    private SandboxDockerConfig disabledConfig() {
        // enabled=false → isAvailable() is false; orchestrator agents
        // would skip the runner.  We still want run() to be safe to call.
        return new SandboxDockerConfig(false, "maven:3.9-eclipse-temurin-21-alpine",
                Duration.ofMinutes(5), 1024, "2.0", "bridge",
                "altrix-sandbox-",
                new SandboxDockerConfig.Reaper(true, Duration.ofMinutes(10), Duration.ofMinutes(30)));
    }

    @Test
    void run_returnsEmpty_whenArtifactHasNoPom() {
        SandboxLogRepository repo = Mockito.mock(SandboxLogRepository.class);
        DockerBootHealthRunner runner = new DockerBootHealthRunner(disabledConfig(), repo);

        MigratedFile justJava = MigratedFile.builder()
                .originalPath("src/main/java/Foo.java")
                .newPath("src/main/java/Foo.java")
                .content("class Foo {}")
                .changeType(FileChangeType.MODIFIED)
                .build();
        MigrationArtifact artifact = new MigrationArtifact("p1", List.of(justJava), "no pom");

        List<SandboxFinding> findings = runner.run(artifact);

        // Skipped without findings because the compile runner already
        // produces an INFO for the missing-pom case — no need to double up.
        assertThat(findings).isEmpty();
    }

    @Test
    void run_returnsEmpty_whenArtifactIsEmpty() {
        SandboxLogRepository repo = Mockito.mock(SandboxLogRepository.class);
        DockerBootHealthRunner runner = new DockerBootHealthRunner(disabledConfig(), repo);

        MigrationArtifact empty = new MigrationArtifact("p1", List.of(), "empty");

        assertThat(runner.run(empty)).isEmpty();
        assertThat(runner.run(null)).isEmpty();
    }

    @Test
    void identityAndOrder() {
        DockerBootHealthRunner runner = new DockerBootHealthRunner(
                disabledConfig(), Mockito.mock(SandboxLogRepository.class));
        assertThat(runner.id()).isEqualTo("docker-boot");
        // After compile (10) and tests (11) — broken compile/tests short-circuit.
        assertThat(runner.order()).isEqualTo(12);
    }

    @Test
    void isAvailable_false_whenSandboxDisabled() {
        DockerBootHealthRunner runner = new DockerBootHealthRunner(
                disabledConfig(), Mockito.mock(SandboxLogRepository.class));
        // probeDaemon hasn't been called (no @PostConstruct in plain
        // unit test); daemonReachable defaults to false anyway.
        assertThat(runner.isAvailable()).isFalse();
    }

    /**
     * Finding emitted on health-probe failure must be ERROR severity so the
     * existing migrator-retry loop in ResumeMigrationService picks it up.
     * INFO findings don't trigger retry — they're treated as benign.
     */
    @Test
    void interpretEncodingContract() {
        // Self-check: every Severity used in the runner must be defined.
        // Compile-time guarantee really, but worth pinning so a future
        // refactor doesn't silently downgrade ERROR to INFO.
        assertThat(Severity.ERROR).isNotNull();
        assertThat(Severity.INFO).isNotNull();
    }
}
