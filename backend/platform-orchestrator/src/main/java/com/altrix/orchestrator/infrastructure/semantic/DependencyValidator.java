package com.altrix.orchestrator.infrastructure.semantic;

import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Category;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Finding;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SemanticValidator Capability 4 — verifies that every Kafka class the
 * migrated code references is actually provided by a Maven dependency on
 * the build.  Catches the "package org.apache.kafka does not exist" class
 * of compile failures BEFORE the sandbox, deterministically.
 *
 * <p>For each {@code import} whose FQN the
 * {@link KafkaMigrationKnowledgeBase} maps to a Maven coordinate
 * ({@code dependencyForClass}), the validator checks the project's
 * {@code pom.xml} declares that artifact.  Missing ones become
 * {@link Category#MISSING_DEPENDENCY} findings.
 *
 * <p>Substring match on {@code <artifactId>…</artifactId>} is intentional:
 * we only need to know whether the dependency is present, not to fully
 * parse the POM.  One finding per missing coordinate (deduped), pointing
 * at the first file that needs it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DependencyValidator {

    private final KafkaMigrationKnowledgeBase knowledgeBase;

    private static final Pattern IMPORT_LINE = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * @param javaFiles path → Java source content.
     * @param pomXml    the project's pom.xml content, or null when absent
     *                  (Gradle projects or no build file → no-op).
     */
    public List<Finding> validate(Map<String, String> javaFiles, String pomXml) {
        if (javaFiles == null || javaFiles.isEmpty() || pomXml == null || pomXml.isBlank()) {
            return List.of();
        }
        // coordinate → first (filePath, importFqn) that needs it.
        Map<String, String[]> missingByCoord = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            String path = e.getKey();
            if (path == null || !path.toLowerCase().endsWith(".java") || e.getValue() == null) continue;
            Matcher m = IMPORT_LINE.matcher(e.getValue());
            while (m.find()) {
                String fqn = m.group(1);
                var coordOpt = knowledgeBase.dependencyForClass(fqn);
                if (coordOpt.isEmpty()) continue;
                String coord = coordOpt.get();
                if (pomDeclares(pomXml, coord)) continue;
                missingByCoord.putIfAbsent(coord, new String[]{path, fqn});
            }
        }

        if (missingByCoord.isEmpty()) return List.of();
        List<Finding> findings = new ArrayList<>(missingByCoord.size());
        missingByCoord.forEach((coord, where) ->
                findings.add(new Finding(Category.MISSING_DEPENDENCY, where[0], -1, coord,
                        "Code imports '" + where[1] + "' but the build is missing dependency '"
                                + coord + "'. Add it to pom.xml.")));
        log.debug("DependencyValidator: {} missing dependency/ies", findings.size());
        return findings;
    }

    /**
     * True when the pom declares the artifact of {@code coordinate}
     * ({@code group:artifact}).  Matches on the artifactId element — robust
     * to version / scope differences.
     */
    private static boolean pomDeclares(String pomXml, String coordinate) {
        int colon = coordinate.indexOf(':');
        String artifactId = colon >= 0 ? coordinate.substring(colon + 1) : coordinate;
        return pomXml.contains("<artifactId>" + artifactId + "</artifactId>");
    }
}
