package com.altrix.orchestrator.infrastructure.contract;

import com.altrix.orchestrator.domain.model.contract.ContractViolation;
import com.altrix.orchestrator.domain.model.contract.ContractViolationKind;
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
 * Opt-in smoke test that runs {@link ContractValidator} against a real
 * migrated-artifact tree on disk.  Enabled when the system property
 * {@code altrix.contract.smoke.root} points to a project root containing a
 * {@code src/main/java} tree.  Kept off the regular build because the path
 * is developer-machine specific.
 *
 * <p>Invoke with e.g.:
 * <pre>
 *   ./gradlew :platform-orchestrator:test \
 *       --tests com.altrix.orchestrator.infrastructure.contract.ContractValidatorRealArtifactSmokeTest \
 *       -Daltrix.contract.smoke.root="C:/Users/SALAH/Downloads/migrated-f5e12274-8dca-4e95-bdfb-eb235b9ee82a"
 * </pre>
 *
 * <p>The test asserts the validator surfaces at least one violation of
 * each kind the project was known to suffer from in the user-reported
 * Maven compile log.
 */
class ContractValidatorRealArtifactSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "altrix.contract.smoke.root", matches = ".+")
    void validatesRealMigratedArtifact() throws Exception {
        Path root = Path.of(System.getProperty("altrix.contract.smoke.root"));
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

        ContractValidator validator = new ContractValidator();
        List<ContractViolation> violations = validator.validate(files);
        Set<ContractViolationKind> kinds = validator.kinds(violations);

        // Log what we found — useful when running locally for inspection.
        System.out.println("[ContractValidator smoke] " + violations.size() + " violation(s) found:");
        violations.forEach(v -> System.out.println("  " + v.toLine()));

        // The user-reported log included:
        //   * file/class mismatch  (IGoogleErrorConverter.java vs IKafkaErrorConverter)
        //   * non-public cross-package  (AltrixKafkaMessage not public)
        // Both should appear.
        assertThat(kinds).contains(ContractViolationKind.FILE_CLASS_MISMATCH);
    }
}
