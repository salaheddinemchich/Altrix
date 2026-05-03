package com.altrix.project.domain.service;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.project.domain.model.Project;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pure domain service — detects build system, config format, and framework
 * by scanning entries inside the uploaded ZIP stream.
 *
 * No I/O framework dependencies. Pure Java only.
 */
@Slf4j
public class BuildSystemDetector {

    private static final int MAX_FILES_TO_SCAN = 100;
    private static final int MAX_FILE_BYTES    = 64 * 1024;

    /**
     * Scans the ZIP stream and returns a new Project with detection results applied.
     *
     * @param project   project entity before detection
     * @param zipStream raw ZIP bytes — caller must close
     * @return new Project instance with READY status and detection fields populated
     */
    public Project detect(Project project, InputStream zipStream) {
        log.debug("Running detection on project '{}'", project.getId());

        DetectionAccumulator acc = new DetectionAccumulator();

        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    processEntry(entry.getName(), zip, acc);
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to scan ZIP for project " + project.getId(), e);
        }

        BuildSystem      buildSystem  = acc.resolveBuildSystem();
        ConfigFormat     configFormat = acc.resolveConfigFormat();
        DetectedFramework framework   = acc.resolveFramework();

        log.debug("Detection result — buildSystem={}, configFormat={}, framework={}",
                buildSystem, configFormat, framework);

        return project.withDetectionApplied(buildSystem, configFormat, framework);
    }

    private void processEntry(
            String name, ZipInputStream zip, DetectionAccumulator acc
    ) throws IOException {
        String lower = name.toLowerCase();

        if (lower.endsWith("build.gradle.kts"))        acc.foundGradleKts    = true;
        else if (lower.endsWith("build.gradle"))        acc.foundGradleGroovy = true;
        else if (lower.endsWith("pom.xml"))             acc.foundMaven        = true;

        if (lower.endsWith("application.yml")
                || lower.endsWith("application.yaml")) acc.foundYaml         = true;
        else if (lower.endsWith("application.properties")) acc.foundProperties = true;

        if (acc.scannedFiles < MAX_FILES_TO_SCAN
                && (lower.endsWith(".java") || lower.endsWith(".kt"))) {
            String content = new String(zip.readNBytes(MAX_FILE_BYTES), StandardCharsets.UTF_8);
            acc.scanForFramework(content);
            acc.scannedFiles++;
        }
    }

    private static final class DetectionAccumulator {
        boolean foundGradleKts     = false;
        boolean foundGradleGroovy  = false;
        boolean foundMaven         = false;
        boolean foundYaml          = false;
        boolean foundProperties    = false;
        int     scannedFiles       = 0;
        int     springBootCount    = 0;
        int     springCount        = 0;
        int     jakartaCount       = 0;
        int     javaxCount         = 0;

        void scanForFramework(String content) {
            if (content.contains("org.springframework.boot"))  springBootCount++;
            if (content.contains("org.springframework."))      springCount++;
            if (content.contains("jakarta.ejb")
                    || content.contains("jakarta.ws.rs")
                    || content.contains("jakarta.inject"))     jakartaCount++;
            if (content.contains("javax.ejb")
                    || content.contains("javax.ws.rs")
                    || content.contains("javax.inject"))       javaxCount++;
        }

        BuildSystem resolveBuildSystem() {
            if (foundGradleKts)    return BuildSystem.GRADLE_KOTLIN;
            if (foundGradleGroovy) return BuildSystem.GRADLE_GROOVY;
            if (foundMaven)        return BuildSystem.MAVEN;
            return BuildSystem.GRADLE_KOTLIN;
        }

        ConfigFormat resolveConfigFormat() {
            if (foundYaml)        return ConfigFormat.YAML;
            if (foundProperties)  return ConfigFormat.PROPERTIES;
            return ConfigFormat.YAML;
        }

        DetectedFramework resolveFramework() {
            if (springBootCount > 0) return DetectedFramework.SPRING_BOOT;
            if (springCount > 0)     return DetectedFramework.SPRING_FRAMEWORK;
            if (jakartaCount > 0)    return DetectedFramework.JAKARTA_EE;
            if (javaxCount > 0)      return DetectedFramework.JAVA_EE;
            return DetectedFramework.SPRING_BOOT;
        }
    }
}
