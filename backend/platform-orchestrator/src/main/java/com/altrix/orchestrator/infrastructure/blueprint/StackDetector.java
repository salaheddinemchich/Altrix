package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.DetectedStack;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers {@link DetectedStack} from a project's build file — the one-line
 * architectural summary the migrator prompt pins so it picks the right
 * APIs (e.g. Jakarta EE → raw kafka-clients, never {@code @KafkaListener}).
 *
 * <p>Substring/regex scan of {@code pom.xml} / {@code build.gradle} is
 * intentional: we only need a strong signal about framework + build
 * system, not a full POM parse.
 */
@Component
public class StackDetector {

    private static final Pattern MAVEN_JAVA_VERSION = Pattern.compile(
            "<maven\\.compiler\\.(?:source|release)>\\s*(\\d+)\\s*</maven\\.compiler");
    private static final Pattern GRADLE_JAVA_VERSION = Pattern.compile(
            "(?:sourceCompatibility|JavaLanguageVersion\\.of)\\s*[=(]?\\s*['\"]?(\\d+)");

    /**
     * @param files path → content of the project (build files + sources).
     */
    public DetectedStack detect(Map<String, String> files) {
        if (files == null || files.isEmpty()) return DetectedStack.unknown();

        String pom = files.getOrDefault("pom.xml", findBySuffix(files, "/pom.xml"));
        String gradle = files.getOrDefault("build.gradle",
                files.getOrDefault("build.gradle.kts",
                        findBySuffix(files, "build.gradle")));
        String build = (pom == null ? "" : pom) + "\n" + (gradle == null ? "" : gradle);

        String buildSystem = pom != null && !pom.isBlank() ? "Maven"
                : gradle != null && !gradle.isBlank()
                    ? (gradle.contains("kotlin") || hasKtsFile(files) ? "Gradle (Kotlin DSL)" : "Gradle (Groovy)")
                    : "unknown";

        String framework = detectFramework(build);
        String runtime = detectRuntime(build);
        String version = detectJavaVersion(pom, gradle);

        return new DetectedStack("Java", version, framework, buildSystem, runtime);
    }

    private String detectFramework(String build) {
        if (build.contains("spring-boot-starter") || build.contains("org.springframework.boot")) {
            return "Spring Boot";
        }
        if (build.contains("quarkus")) return "Quarkus";
        if (build.contains("jakarta.jakartaee-api") || build.contains("jakarta.platform")) {
            return "Jakarta EE";
        }
        if (build.contains("javax.javaee-api")) return "Java EE";
        if (build.contains("microprofile")) return "MicroProfile";
        return "unknown";
    }

    private String detectRuntime(String build) {
        if (build.contains("payara-micro")) return "Payara Micro";
        if (build.contains("quarkus")) return "Quarkus";
        if (build.contains("wildfly")) return "WildFly";
        if (build.contains("openliberty") || build.contains("liberty-maven")) return "Open Liberty";
        if (build.contains("spring-boot-starter")) return "Embedded (Spring Boot)";
        return null;
    }

    private String detectJavaVersion(String pom, String gradle) {
        if (pom != null) {
            Matcher m = MAVEN_JAVA_VERSION.matcher(pom);
            if (m.find()) return m.group(1);
        }
        if (gradle != null) {
            Matcher m = GRADLE_JAVA_VERSION.matcher(gradle);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String findBySuffix(Map<String, String> files, String suffix) {
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey() != null && e.getKey().endsWith(suffix)) return e.getValue();
        }
        return null;
    }

    private static boolean hasKtsFile(Map<String, String> files) {
        return files.keySet().stream().anyMatch(k -> k.endsWith(".gradle.kts"));
    }
}
