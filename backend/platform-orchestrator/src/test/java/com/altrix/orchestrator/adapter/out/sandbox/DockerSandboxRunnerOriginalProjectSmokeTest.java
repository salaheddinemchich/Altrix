package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxLog;
import com.altrix.orchestrator.domain.port.out.SandboxLogRepository;
import com.altrix.orchestrator.infrastructure.config.SandboxDockerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test that drives {@link DockerSandboxRunner} on a real,
 * UN-MIGRATED project tree on disk.  The intent is to prove the sandbox
 * component itself can compile a known-good source set — isolating
 * "is the sandbox broken?" from "is the migrator broken?".
 *
 * <p>Reads every file under the configured project root, builds a
 * {@link MigrationArtifact} containing the ORIGINAL bytes (no migration),
 * and invokes the production runner the same way the workflow graph does.
 *
 * <p>Enable by passing the project root + sandbox.docker.enabled flag:
 *
 * <pre>
 * ./gradlew :platform-orchestrator:test \
 *   --tests "*DockerSandboxRunnerOriginalProjectSmokeTest*" \
 *   -Daltrix.sandbox.smoke.root=C:/Users/SALAH/Projects/test-altrix \
 *   -Daltrix.sandbox.smoke.enabled=true
 * </pre>
 *
 * <p>Disabled by default — runs only when the system property is set so
 * the regular build doesn't depend on a developer-specific path.
 */
class DockerSandboxRunnerOriginalProjectSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "altrix.sandbox.smoke.root", matches = ".+")
    void compilesOriginalProjectThroughTheRealSandboxRunner() throws Exception {
        Path root = Path.of(System.getProperty("altrix.sandbox.smoke.root"));
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Not a directory: " + root);
        }
        if (!Files.exists(root.resolve("pom.xml"))) {
            throw new IllegalStateException("No pom.xml at root: " + root);
        }

        // ── 1. Read every interesting file off disk into a MigrationArtifact ──
        List<MigratedFile> files = new ArrayList<>();
        Path src = root.resolve("src");
        try (Stream<Path> s = Files.walk(src)) {
            s.filter(Files::isRegularFile)
             .forEach(p -> {
                 try {
                     String rel = root.relativize(p).toString().replace('\\', '/');
                     files.add(MigratedFile.builder()
                             .originalPath(rel)
                             .newPath(rel)
                             .content(Files.readString(p))
                             .changeType(FileChangeType.UNCHANGED)
                             .diffSummary("original — passed through sandbox without migration")
                             .build());
                 } catch (Exception e) {
                     throw new RuntimeException("read failed for " + p, e);
                 }
             });
        }
        files.add(MigratedFile.builder()
                .originalPath("pom.xml")
                .newPath("pom.xml")
                .content(Files.readString(root.resolve("pom.xml")))
                .changeType(FileChangeType.UNCHANGED)
                .diffSummary("original pom")
                .build());

        MigrationArtifact artifact = new MigrationArtifact(
                "smoke-" + UUID.randomUUID(),
                files,
                "Original project, no migration applied — sandbox correctness probe",
                null);

        System.out.println("[smoke] staged " + files.size() + " file(s) from " + root);

        // ── 2. Configure the runner exactly like prod (default image + cmd) ──
        SandboxDockerConfig cfg = new SandboxDockerConfig(
                true,                                            // enabled
                "maven:3.9-eclipse-temurin-21-alpine",           // image (default)
                Duration.ofMinutes(10),                           // timeout — first pull may be slow
                1024,                                             // memoryMb
                "2.0",                                            // cpus
                "bridge",                                         // network
                "altrix-smoke-",                                  // workspace prefix
                null);                                            // reaper defaults

        // In-memory log sink so we can inspect what would have been persisted.
        InMemoryLogSink sink = new InMemoryLogSink();
        DockerSandboxRunner runner = new DockerSandboxRunner(cfg, sink);

        // The runner uses @PostConstruct in production; outside Spring we
        // call it explicitly.
        runner.probeDaemon();

        if (!runner.isAvailable()) {
            throw new IllegalStateException(
                    "Docker daemon not reachable — start Docker Desktop and re-run.");
        }

        // ── 3. Drive the production code path ───────────────────────────────
        List<SandboxFinding> findings = runner.run(artifact);

        // ── 4. Surface everything to stdout so the user can see exactly what
        //       the sandbox said about their original project.
        System.out.println("\n[smoke] findings (" + findings.size() + "):");
        findings.forEach(f -> System.out.println("  " + f.severity() + "  " + f.runnerId()
                + "  " + (f.filePath() == null ? "" : f.filePath() + ":" + f.line() + "  ")
                + f.message()));

        SandboxLog persisted = sink.last;
        if (persisted != null) {
            System.out.println("\n[smoke] sandbox log (exit=" + persisted.exitCode()
                    + ", " + persisted.content().length() + " chars):");
            System.out.println(persisted.content());
        }

        long errors = findings.stream()
                .filter(f -> f.severity() == SandboxFinding.Severity.ERROR)
                .count();
        assertThat(errors)
                .as("Original (un-migrated) project should compile cleanly through the real sandbox runner")
                .isZero();
    }

    /** Minimal in-memory {@link SandboxLogRepository} for this smoke test. */
    private static final class InMemoryLogSink implements SandboxLogRepository {
        SandboxLog last;

        @Override
        public void save(SandboxLog log) { this.last = log; }

        @Override
        public java.util.List<SandboxLog> findBySessionId(
                com.altrix.orchestrator.domain.model.session.WorkflowSessionId sessionId) {
            return last == null ? java.util.List.of() : java.util.List.of(last);
        }

        @Override
        public java.util.Optional<SandboxLog> findBySessionIdAndRunnerId(
                com.altrix.orchestrator.domain.model.session.WorkflowSessionId sessionId,
                String runnerId) {
            return last != null && runnerId.equals(last.runnerId())
                    ? java.util.Optional.of(last) : java.util.Optional.empty();
        }
    }
}
