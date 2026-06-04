package com.altrix.orchestrator.infrastructure.leak;

import com.altrix.orchestrator.domain.model.leak.PubSubLeakKind;
import com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test: runs {@link PubSubLeakValidator} against a real
 * migrated-artifact tree on disk.  Enabled when the system property
 * {@code altrix.leak.smoke.root} points to a project root containing a
 * {@code src/main/java} tree.  Use it to confirm the validator reports
 * the same files the sandbox compile log named.
 */
class PubSubLeakRealArtifactSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "altrix.leak.smoke.root", matches = ".+")
    void detectsKnownLeaksInRealMigratedArtifact() throws Exception {
        Path root = Path.of(System.getProperty("altrix.leak.smoke.root"));
        Path srcMain = root.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(srcMain)) {
            throw new IllegalStateException("Not a Java project root: " + root);
        }

        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(srcMain)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            files.put(srcMain.relativize(p).toString().replace('\\', '/'),
                                    Files.readString(p));
                        } catch (Exception ignored) {
                        }
                    });
        }
        // pom.xml + application.yml live at root.
        Path pom = root.resolve("pom.xml");
        if (Files.exists(pom)) files.put("pom.xml", Files.readString(pom));

        PubSubLeakValidator validator = new PubSubLeakValidator();
        List<PubSubLeakViolation> violations = validator.validate(files);
        Set<PubSubLeakKind> kinds = validator.kinds(violations);

        System.out.println("[PubSubLeakValidator smoke] " + violations.size() + " leak(s):");
        violations.forEach(v -> System.out.println("  " + v.toLine()));

        // The compile log listed PullMessagesTask / AcknowledgeMessagesTask /
        // PublishMessagesTask among others.  Confirm at least one Google
        // import leak is flagged.
        assertThat(kinds).contains(PubSubLeakKind.GOOGLE_IMPORT);
        boolean flaggedPullTask = violations.stream()
                .anyMatch(v -> v.filePath().contains("PullMessagesTask"));
        boolean flaggedAckTask = violations.stream()
                .anyMatch(v -> v.filePath().contains("AcknowledgeMessagesTask"));
        boolean flaggedPubTask = violations.stream()
                .anyMatch(v -> v.filePath().contains("PublishMessagesTask"));
        assertThat(flaggedPullTask || flaggedAckTask || flaggedPubTask).isTrue();
    }
}
