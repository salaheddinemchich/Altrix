package com.altrix.orchestrator.infrastructure.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-file consistency check for the migrated artifact.
 *
 * <p>The migrator processes files in isolation — every AI call sees one
 * file.  That makes it easy for the model to rename a class in one file
 * (e.g. {@code PubsubService} → {@code KafkaService}) without realising
 * the new name is never defined anywhere else.  The result is a perfectly
 * compilable-looking file that fails at sandbox compile with
 * "cannot find symbol class KafkaService".
 *
 * <p>This validator catches that pattern: it scans every migrated file
 * once to build the set of declared types in the artifact, then walks
 * every file's intra-project imports and reports any that don't resolve.
 *
 * <p>Imports outside the project's own base package are NOT checked —
 * verifying every {@code org.apache.kafka.*} reference would require a
 * full classpath we don't have.  The retry loop in
 * {@code ResumeMigrationService} feeds compile errors back to the
 * migrator, which is the right layer to recover from library-side
 * hallucinations.
 */
@Slf4j
@Component
public class ProjectSymbolValidator {

    /** Type declarations to index. */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?:^|\\s)(?:public\\s+|final\\s+|abstract\\s+|static\\s+|sealed\\s+|non-sealed\\s+|private\\s+|protected\\s+)*"
            + "(?:class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);

    /** Import statements to validate (skip static imports — those are members). */
    private static final Pattern IMPORT_STMT = Pattern.compile(
            "^\\s*import\\s+(?!static\\s)([\\w.]+);",
            Pattern.MULTILINE);

    /**
     * For each migrated Java file, returns the list of intra-project
     * imports that reference types not declared anywhere in the artifact.
     * An empty map means the artifact is internally consistent.
     *
     * @param projectBasePackage e.g. {@code com.example.altrix} — only
     *                           imports starting with this prefix are
     *                           validated.  Empty string disables the
     *                           check (everything's "external").
     * @param files              path → content map of the migrated artifact.
     */
    public Map<String, Set<String>> findUnresolvedImports(String projectBasePackage,
                                                          Map<String, String> files) {
        if (files == null || files.isEmpty()) return Map.of();
        if (projectBasePackage == null || projectBasePackage.isBlank()) return Map.of();

        Set<String> declaredTypes = indexDeclaredTypes(files);
        Map<String, Set<String>> unresolved = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            if (!path.endsWith(".java")) continue;
            Set<String> badImports = unresolvedIn(e.getValue(), projectBasePackage, declaredTypes);
            if (!badImports.isEmpty()) unresolved.put(path, badImports);
        }
        return Collections.unmodifiableMap(unresolved);
    }

    /**
     * Detects the project's base package from the source set — the longest
     * common prefix of every file's {@code package} declaration.  Returns
     * empty string when files come from multiple roots or none can be
     * read.  Saves the caller from having to guess or hard-code this.
     */
    public String inferBasePackage(Map<String, String> files) {
        if (files == null || files.isEmpty()) return "";
        Pattern pkg = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        String common = null;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            Matcher m = pkg.matcher(e.getValue() == null ? "" : e.getValue());
            if (!m.find()) continue;
            String pkgName = m.group(1);
            common = common == null ? pkgName : longestCommonPackagePrefix(common, pkgName);
            if (common.isEmpty()) return "";
        }
        return common == null ? "" : common;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Set<String> indexDeclaredTypes(Map<String, String> files) {
        Set<String> types = new HashSet<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            String content = e.getValue();
            if (content == null) continue;
            // Don't index inside strings or comments — a SHA-256-good
            // approximation is enough; a few false positives in declared
            // types only relax the check, never tighten it.
            Matcher m = TYPE_DECL.matcher(content);
            while (m.find()) types.add(m.group(1));
        }
        return types;
    }

    private static Set<String> unresolvedIn(String content,
                                            String basePackage,
                                            Set<String> declaredTypes) {
        if (content == null) return Set.of();
        Set<String> bad = new LinkedHashSet<>();
        Matcher m = IMPORT_STMT.matcher(content);
        while (m.find()) {
            String fqn = m.group(1);
            if (!fqn.startsWith(basePackage + ".") && !fqn.equals(basePackage)) continue;
            // Wildcard imports we don't try to resolve.
            if (fqn.endsWith(".*")) continue;
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (!declaredTypes.contains(simple)) bad.add(fqn);
        }
        return bad;
    }

    /** "com.x.y.a" + "com.x.z" → "com.x" (package-level prefix). */
    private static String longestCommonPackagePrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int i = 0;
        while (i < pa.length && i < pb.length && pa[i].equals(pb[i])) i++;
        return String.join(".", java.util.Arrays.copyOfRange(pa, 0, i));
    }
}
