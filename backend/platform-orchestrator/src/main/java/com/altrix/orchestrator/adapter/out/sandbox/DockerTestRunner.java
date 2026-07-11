package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.domain.model.sandbox.SandboxLog;
import com.altrix.orchestrator.domain.port.out.SandboxLogRepository;
import com.altrix.orchestrator.domain.port.out.SandboxRunnerPort;
import com.altrix.orchestrator.infrastructure.config.SandboxDockerConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fourth {@link SandboxRunnerPort} (#96) — runs the project's test suite
 * inside a Maven container and reports failures as findings.
 *
 * <p>Separate runner from {@link DockerSandboxRunner} on purpose: a slow
 * test suite shouldn't block the much faster compile signal that the
 * compile runner produces.  Order 11 (after compile at 10), so a broken
 * compile short-circuits before tests even start (the validator runs
 * all runners but a test failure on un-compileable code is noise).
 *
 * <p>Opt-in via the same {@code sandbox.docker.enabled} flag — and
 * additionally {@code sandbox.docker.run-tests-enabled} (default false
 * because tests are slow and reviewers usually want the compile signal
 * first).  Set both to {@code true} to activate this runner.
 *
 * <p>Open follow-ups: parse Surefire XML reports for per-test
 * granularity (currently parses {@code Tests run: N, Failures: F,
 * Errors: E, Skipped: S} summary lines, which captures the high-level
 * signal but not individual stack traces).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sandbox.docker", name = "run-tests-enabled", havingValue = "true")
public class DockerTestRunner implements SandboxRunnerPort {

    public static final String ID = "docker-test";

    /**
     * Surefire summary line — always emitted, even on success.  Captures
     * test/failure/error/skipped counts.
     */
    private static final Pattern SUREFIRE_SUMMARY = Pattern.compile("Tests run:\\s+(\\d+),\\s+Failures:\\s+(\\d+),\\s+Errors:\\s+(\\d+),\\s+Skipped:\\s+(\\d+)");

    /**
     * Per-failure line — Surefire emits "Failed tests:" / "Errors:" sections
     * with FQN lines beneath.  We capture the FQN to attach severity.
     */
    private static final Pattern SUREFIRE_FAIL_LINE = Pattern.compile("^\\s*([\\w.$]+(?:Test|IT))\\.(\\w+):\\s*(.+)$", Pattern.MULTILINE);

    private final SandboxDockerConfig config;
    private final SandboxLogRepository sandboxLogRepository;
    private volatile DockerClient client;
    private volatile boolean daemonReachable;

    public DockerTestRunner(SandboxDockerConfig config, SandboxLogRepository sandboxLogRepository) {
        this.config = config;
        this.sandboxLogRepository = sandboxLogRepository;
    }

    @PostConstruct
    void probeDaemon() {
        if (!config.enabled()) {
            log.info("[DockerTestRunner] parent sandbox flag disabled — skipped");
            return;
        }
        try {
            this.client = buildClient();
            this.client.pingCmd().exec();
            this.daemonReachable = true;
            log.info("[DockerTestRunner] Docker daemon reachable — runner armed (runs after compile)");
            pullImageIfMissing(config.mavenImage());
        } catch (Exception e) {
            this.daemonReachable = false;
            log.warn("[DockerTestRunner] Docker daemon unreachable ({}) — runner will skip", e.getMessage());
        }
    }

    void pullImageIfMissing(String image) {
        try {
            client.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException expected) {
            // fall through to pull
        } catch (Exception e) {
            log.warn("[DockerTestRunner] could not inspect {} ({}), pulling anyway", image, e.getMessage());
        }
        try {
            log.info("[DockerTestRunner] pulling image {} …", image);
            client.pullImageCmd(image)
                  .exec(new PullImageResultCallback())
                  .awaitCompletion(10, TimeUnit.MINUTES);
            log.info("[DockerTestRunner] image {} ready", image);
        } catch (Exception e) {
            log.warn("[DockerTestRunner] could not pull image {} ({})", image, e.getMessage());
        }
    }

    @PreDestroy
    void closeClient() {
        if (client != null) {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public String id() { return ID; }

    @Override
    public int order() { return 11; }   // after compile (10)

    @Override
    public boolean isAvailable() {
        return config.enabled() && daemonReachable;
    }

    @Override
    public List<SandboxFinding> run(MigrationArtifact artifact) {
        if (artifact == null || artifact.files() == null || artifact.files().isEmpty()) return List.of();
        boolean hasPom = artifact.files().stream().anyMatch(f -> {
            String p = f.newPath() != null ? f.newPath() : f.originalPath();
            return p != null && p.endsWith("pom.xml");
        });
        if (!hasPom) return List.of();   // compile runner already emitted the INFO

        Path workspace = null;
        String containerId = null;
        try {
            workspace = stageWorkspace(artifact);
            containerId = createContainer(workspace);
            // Wait for completion FIRST, then read the full buffered log — a
            // follow-stream attached at start races a short-lived container
            // and can capture nothing (empty sandbox logs).
            int exitCode = waitForExit(containerId);
            String logs = collectLogs(containerId);
            // #105 — persist captured output for post-run review.
            String sessionId = SandboxContext.currentSessionId();
            if (sessionId != null && sandboxLogRepository != null) {
                sandboxLogRepository.save(new SandboxLog(sessionId, ID, logs, exitCode, Instant.now()));
            }
            return interpret(exitCode, logs);
        } catch (Exception e) {
            log.error("[DockerTestRunner] test run failed: {}", e.getMessage());
            return List.of(SandboxFinding.of(ID, Severity.ERROR,
                    "Sandbox tests failed to run: " + e.getMessage()));
        } finally {
            cleanupContainer(containerId);
            cleanupWorkspace(workspace);
        }
    }

    // ── workspace + container (duplicated with DockerSandboxRunner; extract
    //    when a third Docker runner lands) ────────────────────────────────────

    private Path stageWorkspace(MigrationArtifact artifact) throws IOException {
        Path dir = Files.createTempDirectory(config.workspacePrefix() + "test-");
        for (MigratedFile f : artifact.files()) {
            String relPath = f.newPath() != null ? f.newPath() : f.originalPath();
            if (relPath == null || relPath.isBlank() || f.content() == null) continue;
            if (relPath.contains("..")) continue;
            Path target = dir.resolve(relPath).normalize();
            if (!target.startsWith(dir)) continue;
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return dir;
    }

    private void cleanupWorkspace(Path workspace) {
        if (workspace == null) return;
        try (var stream = Files.walk(workspace)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private String createContainer(Path workspace) {
        Map<String, String> labels = new HashMap<>();
        labels.put(DockerSandboxRunner.LABEL_KEY, DockerSandboxRunner.LABEL_VALUE);

        Volume mount = new Volume("/workspace");
        Bind bind = new Bind(workspace.toAbsolutePath().toString(), mount);

        HostConfig host = HostConfig.newHostConfig()
                .withBinds(bind)
                .withMemory((long) config.memoryMb() * 1024L * 1024L)
                .withNanoCPUs((long) (Double.parseDouble(config.cpus()) * 1_000_000_000L))
                .withNetworkMode(config.network());

        CreateContainerResponse created = client.createContainerCmd(config.mavenImage())
                .withName(config.workspacePrefix() + "test-" + UUID.randomUUID())
                .withWorkingDir("/workspace")
                .withHostConfig(host)
                .withVolumes(mount)
                .withLabels(labels)
                .withEntrypoint("mvn")
                .withCmd("-q", "-B", "test")
                .exec();
        client.startContainerCmd(created.getId()).exec();
        return created.getId();
    }

    /** Reads the full buffered log AFTER the container has exited (no follow-stream race). */
    private String collectLogs(String containerId) throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        client.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true)
                .withTailAll()
                .exec(new LogContainerResultCallback() {
                    @Override public void onNext(Frame frame) {
                        sb.append(new String(frame.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        return sb.toString();
    }

    private int waitForExit(String containerId) throws InterruptedException {
        Integer code = client.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        return code != null ? code : 137;
    }

    private void cleanupContainer(String containerId) {
        if (containerId == null) return;
        try { client.removeContainerCmd(containerId).withForce(true).exec(); }
        catch (Exception ignored) {}
    }

    // ── interpretation ───────────────────────────────────────────────────────

    List<SandboxFinding> interpret(int exitCode, String logs) {
        List<SandboxFinding> findings = new ArrayList<>();

        // Aggregate counts from the last Surefire summary line (Maven emits
        // one per module; the final aggregate is what we report on).
        Matcher m = SUREFIRE_SUMMARY.matcher(logs);
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        while (m.find()) {
            tests    = Integer.parseInt(m.group(1));
            failures = Integer.parseInt(m.group(2));
            errors   = Integer.parseInt(m.group(3));
            skipped  = Integer.parseInt(m.group(4));
        }

        if (exitCode == 0 && failures == 0 && errors == 0) {
            String label = tests == 0
                    ? "No tests in project — sandbox test runner had nothing to run"
                    : "Sandbox tests passed (" + tests + " run, " + skipped + " skipped)";
            findings.add(SandboxFinding.of(ID, tests == 0 ? Severity.INFO : Severity.INFO, label));
            return findings;
        }

        // Per-test ERRORs (Failed tests + Errors blocks).
        Matcher line = SUREFIRE_FAIL_LINE.matcher(logs);
        int matched = 0;
        while (line.find() && matched < 50) {
            String fqn = line.group(1) + "." + line.group(2) + "()";
            String msg = line.group(3).trim();
            findings.add(SandboxFinding.of(ID, Severity.ERROR, fqn + ": " + msg));
            matched++;
        }

        // If we couldn't tease apart individual tests but know counts, summarise.
        if (findings.isEmpty() && (failures > 0 || errors > 0)) {
            findings.add(SandboxFinding.of(ID, Severity.ERROR,
                    "Sandbox tests failed: " + failures + " failure(s), " + errors + " error(s) of "
                            + tests + " test(s)"));
        }
        if (findings.isEmpty() && exitCode != 0) {
            String tail = logs.length() > 2_000 ? logs.substring(logs.length() - 2_000) : logs;
            findings.add(SandboxFinding.of(ID, Severity.ERROR,
                    "Sandbox tests exited " + exitCode + " (no parseable failures); tail: " + tail));
        }
        return findings;
    }

    DockerClient buildClient() {
        DefaultDockerClientConfig cfg = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(cfg.getDockerHost())
                .sslConfig(cfg.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        return DockerClientImpl.getInstance(cfg, http);
    }
}
