package com.altrix.orchestrator.infrastructure.blueprint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a Maven project's compile-scope dependency JARs to a list of
 * host {@link Path}s, so the {@link OpenRewriteLstParser} can put them on
 * the LST classpath and fully type-attribute EXTERNAL symbols (kafka-clients,
 * jakarta-api, …) — not just intra-project types.
 *
 * <p>Reuses the same infra as the Docker sandbox runners: it shells out to a
 * Maven image with the host {@code ~/.m2} mounted, runs
 * {@code dependency:build-classpath}, and reads back the classpath the
 * container resolved.  Container repository paths ({@code /root/.m2/...}) are
 * translated to the host repository so the orchestrator JVM (which runs the
 * parser) can read the JARs directly.
 *
 * <p><b>Best-effort by contract:</b> any failure (Docker unavailable, no pom,
 * resolve timeout, parse error) returns an empty list and is logged at WARN —
 * the blueprint then falls back to intra-project-only attribution, exactly as
 * before this resolver existed.  It must NEVER throw into the mapper.
 */
@Slf4j
@Component
public class MavenClasspathResolver {

    /** The container-side Maven local repository (where the mounted ~/.m2 lands). */
    static final String CONTAINER_M2 = "/root/.m2";

    private final boolean enabled;
    private final String mavenImage;
    private final Path hostLocalRepo;
    private final long timeoutSeconds;

    public MavenClasspathResolver(
            @Value("${blueprint.classpath.enabled:true}") boolean enabled,
            @Value("${blueprint.classpath.maven-image:maven:3.9-eclipse-temurin-21-alpine}") String mavenImage,
            @Value("${blueprint.classpath.local-repo:${user.home}/.m2}") String hostLocalRepo,
            @Value("${blueprint.classpath.timeout-seconds:180}") long timeoutSeconds) {
        this.enabled = enabled;
        this.mavenImage = mavenImage;
        this.hostLocalRepo = Path.of(hostLocalRepo);
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * @param pomXml the project's pom.xml content (or null for a Gradle / no
     *               build-file project — returns empty).
     * @return existing JAR paths on the host, or an empty list on any failure.
     */
    public List<Path> resolve(String pomXml) {
        if (!enabled) {
            log.debug("MavenClasspathResolver disabled (blueprint.classpath.enabled=false)");
            return List.of();
        }
        if (pomXml == null || pomXml.isBlank()) return List.of();

        Path work = null;
        try {
            work = Files.createTempDirectory("altrix-cp-");
            Files.writeString(work.resolve("pom.xml"), pomXml, StandardCharsets.UTF_8);

            String classpath = runBuildClasspath(work);
            if (classpath == null || classpath.isBlank()) {
                log.warn("MavenClasspathResolver: empty classpath resolved — external types stay un-attributed");
                return List.of();
            }
            List<Path> jars = parseContainerClasspath(classpath, CONTAINER_M2, hostLocalRepo);
            log.info("MavenClasspathResolver: resolved {} dependency JAR(s) for LST attribution", jars.size());
            return jars;
        } catch (Exception e) {
            log.warn("MavenClasspathResolver: resolution failed ({}); blueprint falls back to "
                    + "intra-project attribution", e.getMessage());
            return List.of();
        } finally {
            if (work != null) deleteQuietly(work);
        }
    }

    /**
     * Runs {@code mvn dependency:build-classpath} in the Maven image and
     * returns the resolved classpath string (container paths), or null.
     */
    private String runBuildClasspath(Path work) throws IOException, InterruptedException {
        String outFile = "altrix-cp.txt";
        List<String> cmd = List.of(
                "docker", "run", "--rm",
                "-v", dockerVolume(work, "/workspace"),
                "-v", dockerVolume(hostLocalRepo, CONTAINER_M2),
                "-w", "/workspace",
                mavenImage,
                "mvn", "-q", "-B", "-ntp",
                "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath",
                "-DincludeScope=compile",
                "-Dmdep.outputFile=" + outFile);

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String console = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            log.warn("MavenClasspathResolver: build-classpath timed out after {}s", timeoutSeconds);
            return null;
        }
        if (p.exitValue() != 0) {
            log.warn("MavenClasspathResolver: mvn exited {} — {}", p.exitValue(),
                    console.lines().reduce((a, b) -> b).orElse("")); // last line
            return null;
        }
        Path out = work.resolve(outFile);
        return Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8) : null;
    }

    /**
     * Translates a container-resolved classpath string into existing host
     * JAR paths.  Splits on both {@code :} (Linux) and {@code ;} (defensive),
     * maps the container repo prefix to the host repo, and keeps only entries
     * that actually exist on the host.
     *
     * <p>Pure + side-effect-free apart from {@link Files#exists} — unit-tested.
     */
    static List<Path> parseContainerClasspath(String classpath, String containerM2, Path hostRepo) {
        List<Path> jars = new ArrayList<>();
        if (classpath == null) return jars;
        String containerRepo = containerM2.endsWith("/repository")
                ? containerM2 : containerM2 + "/repository";
        Path hostRepoRoot = hostRepo.resolve("repository");
        for (String raw : classpath.trim().split("[:;]")) {
            String entry = raw.trim();
            if (entry.isEmpty() || !entry.endsWith(".jar")) continue;
            Path hostPath;
            if (entry.startsWith(containerRepo)) {
                String rel = entry.substring(containerRepo.length()).replaceFirst("^/+", "");
                hostPath = hostRepoRoot.resolve(rel);
            } else {
                hostPath = Path.of(entry); // already a host-resolvable path
            }
            if (Files.exists(hostPath)) jars.add(hostPath);
        }
        return jars;
    }

    /** Docker {@code -v} source must use forward slashes (Windows hosts included). */
    private static String dockerVolume(Path host, String containerMount) {
        return host.toAbsolutePath().toString().replace('\\', '/') + ":" + containerMount;
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(pth -> { try { Files.deleteIfExists(pth); } catch (IOException ignored) { } });
        } catch (IOException ignored) {
            // temp cleanup is best-effort
        }
    }
}
