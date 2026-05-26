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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Third {@link SandboxRunnerPort} (#16/#17/#95) — actually compiles the
 * migrated source tree inside a Maven Docker container and reports any
 * compilation errors as structured findings.
 *
 * <p>Opt-in via {@code sandbox.docker.enabled=true}; default off so a
 * dev box without Docker still boots the orchestrator cleanly.  When
 * enabled, {@link #isAvailable} probes the daemon at startup and
 * returns false if it's unreachable — one bad config can't take the
 * pipeline down.
 *
 * <p>Order 10 — runs after the static + migration-quality runners
 * (orders 0 and 1) because compilation is the most expensive check
 * (minutes vs milliseconds).  Short-circuit-style fail-fast can be
 * layered later by reading the orders 0/1 findings first; for now the
 * validator runs all available runners and aggregates.
 *
 * <p>Security guards:
 * <ul>
 *   <li>Container runs with {@code --memory} + {@code --cpus} limits
 *       so a runaway build can't starve the orchestrator.</li>
 *   <li>Container is killed after {@code sandbox.docker.timeout} and
 *       always removed in a {@code finally}.</li>
 *   <li>Mounted volume is created under the system temp dir and
 *       deleted on exit.</li>
 *   <li>{@code workspace-prefix} label lets the periodic reaper
 *       ({@link DockerSandboxReaper}) clean up containers a crash
 *       might have leaked.</li>
 * </ul>
 */
@Slf4j
@Component
public class DockerSandboxRunner implements SandboxRunnerPort {

    public static final String ID = "docker";
    /** Compile-error pattern Maven 3.x emits — {@code [ERROR] /path/File.java:[42,10] message} */
    private static final Pattern MVN_ERROR = Pattern.compile(
            "\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),\\d+\\]\\s+(.+)");
    /** Label key applied to every container we create so the reaper can find them. */
    static final String LABEL_KEY = "altrix.sandbox";
    static final String LABEL_VALUE = "true";

    private final SandboxDockerConfig config;
    private final SandboxLogRepository sandboxLogRepository;
    private volatile DockerClient client;
    private volatile boolean daemonReachable;

    public DockerSandboxRunner(SandboxDockerConfig config, SandboxLogRepository sandboxLogRepository) {
        this.config = config;
        this.sandboxLogRepository = sandboxLogRepository;
    }

    @PostConstruct
    void probeDaemon() {
        if (!config.enabled()) {
            log.info("[DockerSandboxRunner] disabled via sandbox.docker.enabled=false — skipped");
            return;
        }
        try {
            this.client = buildClient();
            this.client.pingCmd().exec();
            this.daemonReachable = true;
            log.info("[DockerSandboxRunner] Docker daemon reachable — runner armed");
            // Pre-pull the Maven image so the first compile doesn't hit a
            // 404 ("No such image") — the Docker SDK does not auto-pull on
            // createContainer.  Best-effort; if the pull fails the runner
            // is still armed and the failure will surface clearly on first use.
            pullImageIfMissing(config.mavenImage());
        } catch (Exception e) {
            this.daemonReachable = false;
            log.warn("[DockerSandboxRunner] Docker daemon unreachable ({}) — runner will skip", e.getMessage());
        }
    }

    /** Pulls the image if it isn't already in the local Docker cache. */
    void pullImageIfMissing(String image) {
        try {
            client.inspectImageCmd(image).exec();
            return; // already present
        } catch (NotFoundException expected) {
            // fall through to pull
        } catch (Exception e) {
            log.warn("[DockerSandboxRunner] could not inspect {} ({}), attempting pull anyway",
                    image, e.getMessage());
        }
        try {
            log.info("[DockerSandboxRunner] pulling image {} (first run takes a few minutes) …", image);
            client.pullImageCmd(image)
                  .exec(new PullImageResultCallback())
                  .awaitCompletion(10, TimeUnit.MINUTES);
            log.info("[DockerSandboxRunner] image {} ready", image);
        } catch (Exception e) {
            log.warn("[DockerSandboxRunner] could not pull image {} ({}); first run may fail with 404",
                    image, e.getMessage());
        }
    }

    @PreDestroy
    void closeClient() {
        if (client != null) {
            try { client.close(); } catch (IOException ignored) { /* best-effort */ }
        }
    }

    @Override
    public String id() { return ID; }

    @Override
    public int order() { return 10; } // after static (0), migration-quality (1)

    @Override
    public boolean isAvailable() {
        return config.enabled() && daemonReachable;
    }

    @Override
    public List<SandboxFinding> run(MigrationArtifact artifact) {
        if (artifact == null || artifact.files() == null || artifact.files().isEmpty()) return List.of();

        // Maven-only first cut; Gradle support is a follow-up.  Skip with
        // INFO when there's no pom.xml so the validator doesn't pretend
        // it ran a compile.
        boolean hasPom = artifact.files().stream().anyMatch(f -> {
            String p = f.newPath() != null ? f.newPath() : f.originalPath();
            return p != null && p.endsWith("pom.xml");
        });
        if (!hasPom) {
            return List.of(SandboxFinding.of(ID, Severity.INFO,
                    "No pom.xml in artifact — Docker sandbox compile skipped (Gradle support pending)"));
        }

        Path workspace = null;
        String containerId = null;
        try {
            workspace = stageWorkspace(artifact);
            containerId = createContainer(workspace);
            String logs = runAndCollect(containerId);
            int exitCode = waitForExit(containerId);
            // #105 — persist the captured output so reviewers can read it
            // from the JobDetail log viewer after the run.  Best-effort —
            // the SandboxLogRepository swallows persistence errors so the
            // findings still flow downstream.
            persistLog(logs, exitCode);
            return interpret(exitCode, logs);
        } catch (Exception e) {
            log.error("[DockerSandboxRunner] sandbox run failed: {}", e.getMessage());
            return List.of(SandboxFinding.of(ID, Severity.ERROR,
                    "Sandbox compile failed to run: " + e.getMessage()));
        } finally {
            cleanupContainer(containerId);
            cleanupWorkspace(workspace);
        }
    }

    /**
     * Best-effort persistence of the captured stdout/stderr blob.  Skips
     * silently when the SandboxContext isn't populated (e.g. unit tests
     * that call run() directly, or future callers outside the validator
     * pipeline).
     */
    private void persistLog(String logs, int exitCode) {
        String sessionId = SandboxContext.currentSessionId();
        if (sessionId == null || sandboxLogRepository == null) return;
        sandboxLogRepository.save(new SandboxLog(
                sessionId, ID, logs, exitCode, Instant.now()));
    }

    // ── workspace ────────────────────────────────────────────────────────────

    private Path stageWorkspace(MigrationArtifact artifact) throws IOException {
        Path dir = Files.createTempDirectory(config.workspacePrefix());
        for (MigratedFile f : artifact.files()) {
            String relPath = f.newPath() != null ? f.newPath() : f.originalPath();
            if (relPath == null || relPath.isBlank() || f.content() == null) continue;
            // Defence-in-depth against path traversal — the AI shouldn't
            // produce a "..", but be paranoid.
            if (relPath.contains("..")) {
                log.warn("[DockerSandboxRunner] refusing path with '..' segment: {}", relPath);
                continue;
            }
            Path target = dir.resolve(relPath).normalize();
            if (!target.startsWith(dir)) {
                log.warn("[DockerSandboxRunner] path escapes workspace, skipping: {}", relPath);
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        log.debug("[DockerSandboxRunner] staged workspace at {}", dir);
        return dir;
    }

    private void cleanupWorkspace(Path workspace) {
        if (workspace == null) return;
        try {
            try (var stream = Files.walk(workspace)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (IOException e) {
            log.warn("[DockerSandboxRunner] could not delete workspace {}: {}", workspace, e.getMessage());
        }
    }

    // ── container ────────────────────────────────────────────────────────────

    private String createContainer(Path workspace) {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_KEY, LABEL_VALUE);

        Volume mount = new Volume("/workspace");
        Bind bind = new Bind(workspace.toAbsolutePath().toString(), mount);

        HostConfig host = HostConfig.newHostConfig()
                .withBinds(bind)
                .withMemory((long) config.memoryMb() * 1024L * 1024L)
                .withNanoCPUs((long) (Double.parseDouble(config.cpus()) * 1_000_000_000L))
                .withNetworkMode(config.network());

        CreateContainerResponse created = client.createContainerCmd(config.mavenImage())
                .withName(config.workspacePrefix() + UUID.randomUUID())
                .withWorkingDir("/workspace")
                .withHostConfig(host)
                .withVolumes(mount)
                .withLabels(labels)
                .withEntrypoint("mvn")
                // -q  quiet (only errors), -B batch, skip tests so the first
                // signal is just "does the AI's rewrite compile?".  Tests
                // become a separate runner once #96 lands.
                .withCmd("-q", "-B", "-DskipTests", "compile")
                .exec();

        client.startContainerCmd(created.getId()).exec();
        log.debug("[DockerSandboxRunner] started container {} ({})",
                created.getId().substring(0, 12), config.mavenImage());
        return created.getId();
    }

    private String runAndCollect(String containerId) throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        client.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTailAll()
                .exec(new LogContainerResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        sb.append(new String(frame.getPayload()));
                    }
                })
                .awaitCompletion(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        return sb.toString();
    }

    private int waitForExit(String containerId) throws InterruptedException {
        Integer code = client.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        return code != null ? code : 137; // 137 = SIGKILL-ish (timeout)
    }

    private void cleanupContainer(String containerId) {
        if (containerId == null) return;
        try {
            client.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("[DockerSandboxRunner] could not remove container {}: {}",
                    containerId.substring(0, Math.min(12, containerId.length())), e.getMessage());
        }
    }

    // ── interpretation ───────────────────────────────────────────────────────

    private List<SandboxFinding> interpret(int exitCode, String logs) {
        if (exitCode == 0) {
            return List.of(SandboxFinding.of(ID, Severity.INFO,
                    "Sandbox compile passed (mvn -DskipTests compile)"));
        }

        List<SandboxFinding> findings = new ArrayList<>();
        Matcher m = MVN_ERROR.matcher(logs);
        int matched = 0;
        while (m.find() && matched < 50) {        // hard cap to keep payloads bounded
            String path = stripWorkspacePrefix(m.group(1));
            int line   = Integer.parseInt(m.group(2));
            String msg = m.group(3).trim();
            findings.add(new SandboxFinding(ID, Severity.ERROR, path, line, msg));
            matched++;
        }
        if (findings.isEmpty()) {
            // Compile failed but we couldn't parse a specific line.  Show
            // the tail of the log so the reviewer has a chance.
            String tail = logs.length() > 2_000 ? logs.substring(logs.length() - 2_000) : logs;
            findings.add(SandboxFinding.of(ID, Severity.ERROR,
                    "Sandbox compile failed (exit " + exitCode + "); tail: " + tail));
        }
        return findings;
    }

    /** "/workspace/src/.../Foo.java" → "src/.../Foo.java" */
    private static String stripWorkspacePrefix(String absolute) {
        if (absolute == null) return null;
        int idx = absolute.indexOf("/workspace/");
        return idx >= 0 ? absolute.substring(idx + "/workspace/".length()) : absolute;
    }

    // ── client ───────────────────────────────────────────────────────────────

    /** Override-friendly so tests can inject a mock DockerClient. */
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
