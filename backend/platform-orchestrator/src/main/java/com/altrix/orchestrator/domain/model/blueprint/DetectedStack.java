package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;

/**
 * Top-level architectural facts about the uploaded project — extracted
 * from the LST + the build file (pom.xml / build.gradle).
 *
 * <p>The migrator's per-file prompt prepends a one-line stack summary so
 * the model picks the right APIs without re-detecting per file (e.g.
 * "Jakarta EE → use raw kafka-clients, never @KafkaListener").
 *
 * @param language     {@code "Java"}, {@code "Kotlin"}, or whatever the
 *                     LST parser identifies.
 * @param languageVersion  e.g. {@code "17"}.  Best-effort from the build file.
 * @param framework    {@code "Spring Boot 3"}, {@code "Jakarta EE 10"},
 *                     {@code "Quarkus 3"}, etc.
 * @param buildSystem  {@code "Maven"}, {@code "Gradle (Groovy)"},
 *                     {@code "Gradle (Kotlin DSL)"}, …
 * @param runtime      {@code "Payara Micro"}, {@code "WildFly"},
 *                     {@code "OpenLiberty"}, or null when no runtime
 *                     marker is found.
 */
public record DetectedStack(
        String language,
        String languageVersion,
        String framework,
        String buildSystem,
        String runtime
) implements Serializable {

    public static DetectedStack unknown() {
        return new DetectedStack("Java", null, "unknown", "unknown", null);
    }
}
