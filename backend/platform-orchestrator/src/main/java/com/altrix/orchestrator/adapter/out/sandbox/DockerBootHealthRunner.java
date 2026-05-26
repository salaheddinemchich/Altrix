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
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
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
import java.net.HttpURLConnection;
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boot-and-health sandbox runner (#3 — issue from the in-app smoke test).
 *
 * <p>Builds the migrated project with {@code mvn -B -DskipTests package},
 * runs the resulting fat JAR with {@code java -jar target/*.jar}, then
 * polls {@code /actuator/health} (Spring Boot) or {@code /health} (plain
 * webapp) from the orchestrator until the app reports UP, or the deadline
 * fires.  Emits {@code ERROR} findings on any failure — that triggers the
 * existing migrator-retry loop in {@link com.altrix.orchestrator.domain.service.ResumeMigrationService}
 * exactly the same way a compile or test failure does.
 *
 * <p>Opt-in via {@code sandbox.docker.boot-health-enabled=true}.  Off by
 * default because (a) it pulls dependencies on every cold run, which is
 * slow, and (b) it only makes sense when the migrated artifact is a
 * runnable Spring Boot / Jakarta EE webapp.
 *
 * <p>Order 12 — after compile (10) and tests (11), so a broken compile
 * short-circuits before we burn maven minutes packaging a doomed build.
 *
 * <p>The runner is internet-egress-heavy on the first run (maven needs to
 * download every transitive dep into the container's {@code ~/.m2}).
 * That's intentional for the smoke test scenario — a self-contained boot
 * exercises the migrated code end-to-end the same way a real CI run
 * would.  Subsequent runs reuse the layer cache of {@link SandboxDockerConfig#mavenImage()}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sandbox.docker", name = "boot-health-enabled", havingValue = "true")
public class DockerBootHealthRunner implements SandboxRunnerPort {

    public static final String ID = "docker-boot";

    /** Where the application listens INSIDE the container.  Default 8080. */
    private static final int CONTAINER_PORT = 8080;

    /**
     * Paths we try, in order.
     * <ul>
     *   <li>{@code /actuator/health} — Spring Boot Actuator default.</li>
     *   <li>{@code /health} — convention for plain webapps / Micronaut /
     *       MicroProfile Health at root.</li>
     *   <li>{@code /api/health} — JAX-RS apps that put their resources under
     *       an {@code @ApplicationPath("/api")}, including the Altrix sample
     *       Jakarta EE test project.</li>
     * </ul>
     */
    private static final List<String> HEALTH_PATHS = List.of(
            "/actuator/health",
            "/health",
            "/api/health"
    );

    /** How long to wait for the app to come up.  Generous because Maven
     *  resolution + Spring Boot startup can both be slow on a cold cache. */
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(8);

    /** Poll cadence once the container is running. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** How often we flush the captured log to the repository so the UI
     *  sees progress mid-run instead of an empty box for 8 minutes. */
    private static final Duration LIVE_LOG_FLUSH_INTERVAL = Duration.ofSeconds(3);

    private final SandboxDockerConfig config;
    private final SandboxLogRepository sandboxLogRepository;
    private volatile DockerClient client;
    private volatile boolean daemonReachable;

    public DockerBootHealthRunner(SandboxDockerConfig config, SandboxLogRepository sandboxLogRepository) {
        this.config = config;
        this.sandboxLogRepository = sandboxLogRepository;
    }

    @PostConstruct
    void probeDaemon() {
        if (!config.enabled()) {
            log.info("[DockerBootHealthRunner] parent sandbox.docker.enabled=false — skipped");
            return;
        }
        try {
            this.client = buildClient();
            this.client.pingCmd().exec();
            this.daemonReachable = true;
            log.info("[DockerBootHealthRunner] Docker daemon reachable — runner armed (boots app, hits /health)");
            pullImageIfMissing(config.mavenImage());
        } catch (Exception e) {
            this.daemonReachable = false;
            log.warn("[DockerBootHealthRunner] Docker daemon unreachable ({}) — runner will skip", e.getMessage());
        }
    }

    /** Pulls the Maven image so the first container create doesn't 404. */
    void pullImageIfMissing(String image) {
        try {
            client.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException expected) {
            // fall through to pull
        } catch (Exception e) {
            log.warn("[DockerBootHealthRunner] could not inspect {} ({}), pulling anyway",
                    image, e.getMessage());
        }
        try {
            log.info("[DockerBootHealthRunner] pulling image {} …", image);
            client.pullImageCmd(image)
                  .exec(new PullImageResultCallback())
                  .awaitCompletion(10, TimeUnit.MINUTES);
            log.info("[DockerBootHealthRunner] image {} ready", image);
        } catch (Exception e) {
            log.warn("[DockerBootHealthRunner] could not pull image {} ({})", image, e.getMessage());
        }
    }

    @PreDestroy
    void closeClient() {
        if (client != null) {
            try { client.close(); } catch (IOException ignored) { /* best-effort */ }
        }
    }

    @Override public String id()           { return ID; }
    @Override public int    order()        { return 12; }  // after compile (10) and tests (11)
    @Override public boolean isAvailable() { return config.enabled() && daemonReachable; }

    @Override
    public List<SandboxFinding> run(MigrationArtifact artifact) {
        if (artifact == null || artifact.files() == null || artifact.files().isEmpty()) return List.of();

        boolean hasPom = artifact.files().stream().anyMatch(f -> {
            String p = f.newPath() != null ? f.newPath() : f.originalPath();
            return p != null && p.endsWith("pom.xml");
        });
        if (!hasPom) {
            // The compile runner already emitted an INFO finding for the
            // missing-pom case — no need to double up.
            return List.of();
        }

        Path workspace = null;
        String containerId = null;
        // Captured asynchronously by the log callback so we can both stream
        // it to the SLF4J debug log AND persist the final blob.
        AtomicReference<StringBuilder> capture = new AtomicReference<>(new StringBuilder());
        try {
            workspace = stageWorkspace(artifact);
            containerId = createContainer(workspace);
            int hostPort = startAndDiscoverPort(containerId);
            attachLogStream(containerId, capture.get());

            HealthOutcome outcome = pollHealth(hostPort, capture);

            persistLog(capture.get().toString(), outcome.exitCode);
            return interpret(outcome, capture.get().toString());
        } catch (Exception e) {
            log.error("[DockerBootHealthRunner] boot-health run failed: {}", e.getMessage());
            persistLog(capture.get().toString(), -1);
            return List.of(SandboxFinding.of(ID, Severity.ERROR,
                    "Boot-health runner failed to run: " + e.getMessage()));
        } finally {
            cleanupContainer(containerId);
            cleanupWorkspace(workspace);
        }
    }

    private void persistLog(String logs, int exitCode) {
        String sessionId = SandboxContext.currentSessionId();
        if (sessionId == null || sandboxLogRepository == null) return;
        sandboxLogRepository.save(new SandboxLog(
                sessionId, ID, logs, exitCode, Instant.now()));
    }

    // ── workspace ────────────────────────────────────────────────────────────

    private Path stageWorkspace(MigrationArtifact artifact) throws IOException {
        Path dir = Files.createTempDirectory(config.workspacePrefix() + "boot-");
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

    // ── container ────────────────────────────────────────────────────────────

    /**
     * Single container does both build and run via a shell entrypoint.  The
     * fat-JAR path uses a glob because we don't know the artifact name —
     * the Maven Spring Boot plugin emits {@code target/<finalName>.jar}
     * and finalName defaults to artifactId-version, which we don't have.
     */
    private String createContainer(Path workspace) {
        Map<String, String> labels = new HashMap<>();
        labels.put(DockerSandboxRunner.LABEL_KEY, DockerSandboxRunner.LABEL_VALUE);

        Volume mount = new Volume("/workspace");
        Bind bind = new Bind(workspace.toAbsolutePath().toString(), mount);

        ExposedPort tcp = ExposedPort.tcp(CONTAINER_PORT);
        // Asking Docker for an empty host-port spec → daemon assigns a free port.
        Ports portBindings = new Ports();
        portBindings.bind(tcp, Ports.Binding.empty());

        HostConfig host = HostConfig.newHostConfig()
                .withBinds(bind)
                .withMemory((long) config.memoryMb() * 1024L * 1024L)
                .withNanoCPUs((long) (Double.parseDouble(config.cpus()) * 1_000_000_000L))
                .withNetworkMode(config.network())
                .withPortBindings(portBindings);

        CreateContainerResponse created = client.createContainerCmd(config.mavenImage())
                .withName(config.workspacePrefix() + "boot-" + UUID.randomUUID())
                .withWorkingDir("/workspace")
                .withHostConfig(host)
                .withVolumes(mount)
                .withExposedPorts(tcp)
                .withLabels(labels)
                .withEntrypoint("sh", "-c")
                .withCmd(buildScript())
                .exec();

        return created.getId();
    }

    /**
     * The shell command we run inside the container.  Build first so a
     * compile failure surfaces in the log; then exec into the produced JAR.
     * {@code exec} replaces the shell so signal handling (and the eventual
     * container removal on stop) work cleanly.
     */
    private String buildScript() {
        return "set -e; "
             + "mvn -B -DskipTests package; "
             + "JAR=$(ls target/*.jar 2>/dev/null | grep -v '\\-sources\\|\\-javadoc' | head -1); "
             + "if [ -z \"$JAR\" ]; then echo '[boot-health] no jar built under target/'; exit 2; fi; "
             + "echo \"[boot-health] launching $JAR\"; "
             + "exec java -jar \"$JAR\" --server.port=" + CONTAINER_PORT;
    }

    /** Start the container, then read back the host port Docker actually assigned. */
    private int startAndDiscoverPort(String containerId) {
        client.startContainerCmd(containerId).exec();
        log.debug("[DockerBootHealthRunner] started container {}", containerId.substring(0, 12));

        InspectContainerResponse inspect = client.inspectContainerCmd(containerId).exec();
        Ports.Binding[] bindings = inspect.getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(ExposedPort.tcp(CONTAINER_PORT));
        if (bindings == null || bindings.length == 0 || bindings[0].getHostPortSpec() == null) {
            throw new IllegalStateException("Docker did not assign a host port for " + CONTAINER_PORT);
        }
        int hostPort = Integer.parseInt(bindings[0].getHostPortSpec());
        log.debug("[DockerBootHealthRunner] container port {} → host port {}", CONTAINER_PORT, hostPort);
        return hostPort;
    }

    /**
     * Attach the log stream asynchronously so the captured StringBuilder
     * accumulates output while we're polling the health endpoint.  We don't
     * await completion here — the cleanup path will tear the container
     * down when polling finishes.
     */
    private void attachLogStream(String containerId, StringBuilder sink) {
        client.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true)
                .withFollowStream(true).withTailAll()
                .exec(new LogContainerResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        synchronized (sink) {
                            sink.append(new String(frame.getPayload()));
                        }
                    }
                });
    }

    // ── health polling ───────────────────────────────────────────────────────

    private record HealthOutcome(boolean healthy, int exitCode, String reason, String pathUsed) {}

    /**
     * Polls {@code /actuator/health} then {@code /health} every {@link #POLL_INTERVAL}
     * until the deadline.  Returns the moment any path returns 2xx.  A
     * connection refused is treated as "still starting" — many seconds of
     * those are normal during Spring Boot startup.
     *
     * <p>Every {@link #LIVE_LOG_FLUSH_INTERVAL} we upsert the accumulated
     * log to the repository so the frontend's auto-refresh can show
     * progress mid-run — without this the user just sees an empty box
     * for 5–8 minutes while the JAR builds and the app starts.
     */
    private HealthOutcome pollHealth(int hostPort, AtomicReference<StringBuilder> capture) throws InterruptedException {
        Instant deadline = Instant.now().plus(BOOT_TIMEOUT);
        Instant nextFlush = Instant.now().plus(LIVE_LOG_FLUSH_INTERVAL);
        String lastFailureReason = "never returned 2xx";
        while (Instant.now().isBefore(deadline)) {
            for (String path : HEALTH_PATHS) {
                try {
                    int code = httpGetStatus("http://localhost:" + hostPort + path);
                    if (code >= 200 && code < 300) {
                        synchronized (capture.get()) {
                            capture.get().append("\n[boot-health] OK ").append(path)
                                    .append(" returned ").append(code).append("\n");
                        }
                        return new HealthOutcome(true, 0,
                                "Health probe " + path + " returned " + code, path);
                    }
                    lastFailureReason = path + " returned HTTP " + code;
                } catch (IOException e) {
                    // Connection refused while the app is still booting is expected.
                    lastFailureReason = path + ": " + e.getMessage();
                }
            }
            if (Instant.now().isAfter(nextFlush)) {
                flushLiveLog(capture.get());
                nextFlush = Instant.now().plus(LIVE_LOG_FLUSH_INTERVAL);
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
        }
        return new HealthOutcome(false, 124,    // 124 = timeout (coreutils convention)
                "Boot-health timed out after " + BOOT_TIMEOUT + " — last attempt: " + lastFailureReason,
                null);
    }

    /**
     * Best-effort incremental upsert of the captured log.  We use
     * exitCode = -2 to mean "still running" so the frontend can hide
     * the exit-code chip until the run actually ends.  Swallows all
     * errors — this is observability, never a fatal path.
     */
    private void flushLiveLog(StringBuilder capture) {
        String sessionId = SandboxContext.currentSessionId();
        if (sessionId == null || sandboxLogRepository == null) return;
        String snapshot;
        synchronized (capture) { snapshot = capture.toString(); }
        if (snapshot.isEmpty()) return;
        try {
            sandboxLogRepository.save(new SandboxLog(
                    sessionId, ID, snapshot, -2, Instant.now()));
        } catch (Exception ignored) {
            // observability must not break the run
        }
    }

    /** Plain HEAD-like GET — we only look at the status code. */
    int httpGetStatus(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(1_000);
        conn.setReadTimeout(2_000);
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private void cleanupContainer(String containerId) {
        if (containerId == null) return;
        try { client.removeContainerCmd(containerId).withForce(true).exec(); }
        catch (Exception e) {
            log.warn("[DockerBootHealthRunner] could not remove container {}: {}",
                    containerId.substring(0, Math.min(12, containerId.length())), e.getMessage());
        }
    }

    // ── interpretation ───────────────────────────────────────────────────────

    private List<SandboxFinding> interpret(HealthOutcome outcome, String logs) {
        List<SandboxFinding> findings = new ArrayList<>();
        if (outcome.healthy()) {
            findings.add(SandboxFinding.of(ID, Severity.INFO,
                    "Migrated app booted and " + outcome.pathUsed() + " returned 2xx"));
            return findings;
        }
        // ERROR-severity finding → the validator surfaces it, the migrator
        // retry loop kicks in (#98 / ResumeMigrationService.maxRetries).
        String tail = logs.length() > 4_000 ? logs.substring(logs.length() - 4_000) : logs;
        String hint = "";
        // Connection-refused tail suggests the app never opened the port —
        // probably no embedded server (e.g. missing actuator / web starter).
        // Surface that explicitly so the migrator retry has something concrete
        // to fix instead of guessing.
        if (outcome.reason() != null && outcome.reason().contains("Connection refused")) {
            hint = "\nHint: the app never started listening on port " + CONTAINER_PORT
                 + ". If this is a Spring Boot project, ensure spring-boot-starter-web "
                 + "and spring-boot-starter-actuator (for /actuator/health) are on the "
                 + "classpath, or add a minimal HTTP /health endpoint.";
        }
        findings.add(SandboxFinding.of(ID, Severity.ERROR,
                "Migrated app failed health check: " + outcome.reason() + hint
                        + "\n--- tail of container output ---\n" + tail));
        return findings;
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

    /**
     * Pre-baked entry point for unit tests.  Public so tests can drive the
     * polling without standing up a real container.  We only override
     * {@link #httpGetStatus} in tests.
     */
    @SuppressWarnings("unused")
    static class PollLogic {
    }
}
