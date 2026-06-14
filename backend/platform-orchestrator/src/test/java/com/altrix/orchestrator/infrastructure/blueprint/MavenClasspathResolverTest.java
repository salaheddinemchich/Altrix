package com.altrix.orchestrator.infrastructure.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathResolverTest {

    /** A disabled resolver must short-circuit to empty without touching Docker. */
    @Test
    void disabledResolverReturnsEmpty() {
        var resolver = new MavenClasspathResolver(false, "maven", System.getProperty("user.home") + "/.m2", 5);
        assertThat(resolver.resolve("<project/>")).isEmpty();
    }

    @Test
    void nullOrBlankPomReturnsEmpty() {
        var resolver = new MavenClasspathResolver(true, "maven", System.getProperty("user.home") + "/.m2", 5);
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("   ")).isEmpty();
    }

    // ── pure path-translation logic ─────────────────────────────────────────

    @Test
    void translatesContainerRepoPathsToExistingHostJars(@TempDir Path hostM2) throws Exception {
        // Lay out a fake host repository with two real jar files.
        Path repo = Files.createDirectories(hostM2.resolve("repository"));
        Path kafka = repo.resolve("org/apache/kafka/kafka-clients/3.7.1/kafka-clients-3.7.1.jar");
        Path jakarta = repo.resolve("jakarta/platform/jakarta.jakartaee-api/10.0.0/jakarta.jakartaee-api-10.0.0.jar");
        Files.createDirectories(kafka.getParent());
        Files.createDirectories(jakarta.getParent());
        Files.writeString(kafka, "x");
        Files.writeString(jakarta, "x");

        // A classpath string as the container's mvn build-classpath would emit it.
        String containerCp = MavenClasspathResolver.CONTAINER_M2 + "/repository/org/apache/kafka/"
                + "kafka-clients/3.7.1/kafka-clients-3.7.1.jar:"
                + MavenClasspathResolver.CONTAINER_M2 + "/repository/jakarta/platform/"
                + "jakarta.jakartaee-api/10.0.0/jakarta.jakartaee-api-10.0.0.jar";

        List<Path> jars = MavenClasspathResolver.parseContainerClasspath(
                containerCp, MavenClasspathResolver.CONTAINER_M2, hostM2);

        assertThat(jars).containsExactlyInAnyOrder(kafka, jakarta);
    }

    @Test
    void dropsEntriesThatDoNotExistOnHost(@TempDir Path hostM2) {
        String containerCp = MavenClasspathResolver.CONTAINER_M2
                + "/repository/no/such/lib/1.0/lib-1.0.jar";
        List<Path> jars = MavenClasspathResolver.parseContainerClasspath(
                containerCp, MavenClasspathResolver.CONTAINER_M2, hostM2);
        assertThat(jars).isEmpty();
    }

    @Test
    void ignoresNonJarAndBlankEntries(@TempDir Path hostM2) {
        String cp = "  :/tmp/classes:" + MavenClasspathResolver.CONTAINER_M2 + "/repository/x.txt:";
        assertThat(MavenClasspathResolver.parseContainerClasspath(
                cp, MavenClasspathResolver.CONTAINER_M2, hostM2)).isEmpty();
    }
}
