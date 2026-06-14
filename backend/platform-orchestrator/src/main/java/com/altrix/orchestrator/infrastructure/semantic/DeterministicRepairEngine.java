package com.altrix.orchestrator.infrastructure.semantic;

import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic (no-AI) repair of migrated Java files — SemanticValidator
 * Capability 6, the deterministic-first tier.
 *
 * <p>Applies only mechanically-certain fixes from the
 * {@link KafkaMigrationKnowledgeBase}; anything ambiguous is left for the
 * AI repair tier (wired in a later stage).  PoC scope is import hygiene,
 * which is where the bulk of "cannot find symbol" / "package does not
 * exist" compile failures originate:
 *
 * <ol>
 *   <li><b>Add missing import</b> — a known Kafka type (from the KB's
 *       {@code knownTypeImports} index) is used as a token but has no
 *       matching {@code import}, no wildcard import of its package, and is
 *       not declared locally → insert the import.</li>
 *   <li><b>Remove forbidden + unused import</b> — an import on the KB
 *       forbidden list whose simple name never appears in the body → drop
 *       the import line.  Conservative: a forbidden import that IS used in
 *       the body is left untouched (removing it would change a different
 *       compile error into a worse one); the AI tier handles those.</li>
 * </ol>
 *
 * <p>Pure + idempotent: running it twice produces the same output, and it
 * never throws — a file it can't parse is returned unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeterministicRepairEngine {

    private final KafkaMigrationKnowledgeBase knowledgeBase;

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+[\\w.]+\\s*;", Pattern.MULTILINE);
    /** Package line that may contain path separators ('/' or '\\') the model wrongly emitted. */
    private static final Pattern PACKAGE_WITH_SEP =
            Pattern.compile("^(\\s*package\\s+)([\\w./\\\\]+)(\\s*;)", Pattern.MULTILINE);
    private static final Pattern IMPORT_LINE =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;\\s*$", Pattern.MULTILINE);

    /**
     * Hallucinated Kafka package prefix → the real one.  The free-tier model
     * routinely drops the {@code .clients} segment.  Stable reference data
     * (Kafka's package layout doesn't move), applied to imports AND inline
     * fully-qualified usages alike.  Keys must NOT be substrings of their
     * values (they aren't — the correct paths contain {@code .clients.} which
     * the wrong ones lack), so a plain replace can't double-apply.
     */
    private static final Map<String, String> KAFKA_PACKAGE_CORRECTIONS = Map.of(
            "org.apache.kafka.admin.",    "org.apache.kafka.clients.admin.",
            "org.apache.kafka.producer.", "org.apache.kafka.clients.producer.",
            "org.apache.kafka.consumer.", "org.apache.kafka.clients.consumer.");

    /** Specific Kafka classes the model puts in the wrong package (exact FQN → correct FQN). */
    private static final Map<String, String> KAFKA_CLASS_CORRECTIONS = Map.of(
            // The base exception lives in ...common, NOT ...common.errors
            // (...errors holds the *subtypes* like SerializationException).
            "org.apache.kafka.common.errors.KafkaException", "org.apache.kafka.common.KafkaException");

    /** {@code implements Object {} / {@code extends Object {} — always invalid; remove the clause. */
    private static final Pattern IMPLEMENTS_OBJECT = Pattern.compile("\\s+implements\\s+Object(\\s*\\{)");
    private static final Pattern EXTENDS_OBJECT     = Pattern.compile("\\s+extends\\s+Object(\\s+implements|\\s*\\{)");

    /**
     * {@code java.lang} simple names that need no import.  An import of one of
     * these from any package other than {@code java.lang} is bogus (e.g.
     * {@code import org.apache.kafka.common.errors.RuntimeException;}) — the
     * JDK type resolves automatically, so the import is just removed.
     */
    private static final java.util.Set<String> JAVA_LANG_SIMPLE = java.util.Set.of(
            "String", "Object", "Integer", "Long", "Double", "Float", "Short", "Byte",
            "Boolean", "Character", "Number", "Math", "System", "Thread", "Runnable",
            "Void", "Class", "Enum", "Iterable", "Comparable", "CharSequence",
            "StringBuilder", "StringBuffer", "Exception", "RuntimeException", "Throwable",
            "Error", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "UnsupportedOperationException", "IndexOutOfBoundsException",
            "ClassCastException", "NumberFormatException", "InterruptedException");

    /**
     * Ubiquitous non-Kafka types the migrator uses but routinely forgets to
     * import — especially on the cluster path, which skips the per-file import
     * guards.  Simple name → canonical FQN; added when used-but-not-imported.
     * Deliberately omits ambiguous names (e.g. {@code Value}, which collides
     * between Lombok and Spring).
     */
    private static final Map<String, String> COMMON_TYPE_IMPORTS = Map.ofEntries(
            Map.entry("RequiredArgsConstructor", "lombok.RequiredArgsConstructor"),
            Map.entry("AllArgsConstructor",      "lombok.AllArgsConstructor"),
            Map.entry("NoArgsConstructor",       "lombok.NoArgsConstructor"),
            Map.entry("Getter",                  "lombok.Getter"),
            Map.entry("Setter",                  "lombok.Setter"),
            Map.entry("Data",                    "lombok.Data"),
            Map.entry("Builder",                 "lombok.Builder"),
            Map.entry("Slf4j",                   "lombok.extern.slf4j.Slf4j"),
            Map.entry("SneakyThrows",            "lombok.SneakyThrows"),
            Map.entry("Logger",                  "org.slf4j.Logger"),
            Map.entry("LoggerFactory",           "org.slf4j.LoggerFactory"),
            // Ubiquitous java.util / java.time collections the cluster path
            // often drops.  Unambiguous simple names only.
            Map.entry("List",                    "java.util.List"),
            Map.entry("Map",                     "java.util.Map"),
            Map.entry("Set",                     "java.util.Set"),
            Map.entry("Collection",              "java.util.Collection"),
            Map.entry("Collections",             "java.util.Collections"),
            Map.entry("ArrayList",               "java.util.ArrayList"),
            Map.entry("LinkedList",              "java.util.LinkedList"),
            Map.entry("HashMap",                 "java.util.HashMap"),
            Map.entry("LinkedHashMap",           "java.util.LinkedHashMap"),
            Map.entry("HashSet",                 "java.util.HashSet"),
            Map.entry("Optional",                "java.util.Optional"),
            Map.entry("Arrays",                  "java.util.Arrays"),
            Map.entry("Properties",              "java.util.Properties"),
            Map.entry("Duration",                "java.time.Duration"));

    /** A hand-written {@code Logger log = LoggerFactory.getLogger(...)} field. */
    private static final Pattern MANUAL_LOGGER =
            Pattern.compile("\\bLogger\\s+\\w+\\s*=\\s*LoggerFactory");

    /**
     * Repairs every {@code .java} entry in {@code files}.  Non-Java files
     * pass through untouched.
     *
     * @return the repaired file map (same keys) + the list of actions taken.
     */
    public RepairResult repair(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return new RepairResult(files == null ? Map.of() : files, List.of());
        }
        Map<String, String> knownImports = knowledgeBase.knownTypeImports();
        java.util.Set<String> forbidden = knowledgeBase.allForbiddenImports();

        Map<String, String> out = new LinkedHashMap<>();
        List<RepairAction> actions = new ArrayList<>();

        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || !path.toLowerCase().endsWith(".java") || content == null) {
                out.put(path, content);
                continue;
            }
            try {
                String repaired = repairOneFile(path, content, knownImports, forbidden, actions);
                out.put(path, repaired);
            } catch (Exception ex) {
                log.debug("Deterministic repair skipped for '{}' ({})", path, ex.getMessage());
                out.put(path, content);
            }
        }
        return new RepairResult(out, actions);
    }

    private String repairOneFile(String path, String content,
                                 Map<String, String> knownImports,
                                 java.util.Set<String> forbidden,
                                 List<RepairAction> actions) {
        // ── 0c. Fix a package declaration that uses path separators ──────
        // The cluster migrator sometimes derives the package from the file
        // PATH and emits `package com.example/altrix/pubsub/tasks;` — a
        // syntax error ("';' expected").  Package names never contain '/' or
        // '\\', so converting them to '.' is mechanically certain.
        Matcher pkgSep = PACKAGE_WITH_SEP.matcher(content);
        if (pkgSep.find()) {
            String pkg = pkgSep.group(2);
            if (pkg.indexOf('/') >= 0 || pkg.indexOf('\\') >= 0) {
                String fixed = pkg.replace('/', '.').replace('\\', '.');
                content = content.substring(0, pkgSep.start(2)) + fixed + content.substring(pkgSep.end(2));
                actions.add(new RepairAction(RepairAction.Type.FIX_PACKAGE_DECLARATION, path,
                        "fixed package separators in '" + pkg + "' -> '" + fixed + "'"));
            }
        }

        // ── 0e. Remove invalid `implements Object` / `extends Object` ────
        // A single `implements Object` is a hard compile error that ABORTS
        // Lombok annotation processing for the whole round — every @Data/
        // @Getter/@Slf4j member then "cannot find symbol", cascading one
        // mistake into hundreds.  Object is never an interface and is the
        // implicit supertype, so the clause is always removable.
        String beforeObj = content;
        content = IMPLEMENTS_OBJECT.matcher(content).replaceAll("$1");
        content = EXTENDS_OBJECT.matcher(content).replaceAll("$1");
        if (!content.equals(beforeObj)) {
            actions.add(new RepairAction(RepairAction.Type.REMOVE_INVALID_OBJECT_SUPERTYPE, path,
                    "removed invalid 'implements/extends Object'"));
        }

        // ── 0f. Correct specific misplaced Kafka classes ────────────────
        for (Map.Entry<String, String> c : KAFKA_CLASS_CORRECTIONS.entrySet()) {
            if (content.contains(c.getKey())) {
                content = content.replace(c.getKey(), c.getValue());
                actions.add(new RepairAction(RepairAction.Type.FIX_PACKAGE_PREFIX, path,
                        "corrected class '" + c.getKey() + "' -> '" + c.getValue() + "'"));
            }
        }

        // ── 0d. Drop redundant @Slf4j when a manual Logger field exists ──
        // The model sometimes emits BOTH @Slf4j (which generates `log`) and a
        // hand-written `Logger log = LoggerFactory.getLogger(...)` → duplicate
        // `log`.  Keep the explicit field, drop the annotation.
        if (content.contains("@Slf4j") && MANUAL_LOGGER.matcher(content).find()) {
            String stripped = content.replaceAll("(?m)^[ \\t]*@Slf4j[ \\t]*\\r?\\n", "");
            if (!stripped.equals(content)) {
                content = stripped;
                actions.add(new RepairAction(RepairAction.Type.STRIP_REDUNDANT_SLF4J, path,
                        "removed redundant @Slf4j (a manual Logger field is present)"));
            }
        }

        // ── 0a. Correct hallucinated Kafka package prefixes ──────────────
        // Fixes both `import org.apache.kafka.admin.AdminClient;` and inline
        // `org.apache.kafka.admin.AdminClient field;` — done first so the
        // import-hygiene steps below see the corrected packages.
        for (Map.Entry<String, String> c : KAFKA_PACKAGE_CORRECTIONS.entrySet()) {
            if (content.contains(c.getKey())) {
                content = content.replace(c.getKey(), c.getValue());
                actions.add(new RepairAction(RepairAction.Type.FIX_PACKAGE_PREFIX, path,
                        "corrected package '" + c.getKey() + "' -> '" + c.getValue() + "'"));
            }
        }

        // ── 0b. Remove bogus imports of java.lang types ──────────────────
        // A java.lang simple name imported from a non-java.lang package is
        // always wrong (e.g. org.apache.kafka.common.errors.RuntimeException);
        // the JDK type resolves without an import, so just drop the line.
        Matcher jl = IMPORT_LINE.matcher(content);
        List<String> bogusJdkLines = new ArrayList<>();
        while (jl.find()) {
            String fqn = jl.group(1);
            String simple = simpleName(fqn);
            int lastDot = fqn.lastIndexOf('.');
            String pkg = lastDot > 0 ? fqn.substring(0, lastDot) : "";
            if (JAVA_LANG_SIMPLE.contains(simple) && !pkg.equals("java.lang")) {
                bogusJdkLines.add(jl.group().strip());
                actions.add(new RepairAction(RepairAction.Type.REMOVE_BOGUS_JDK_IMPORT, path,
                        "removed bogus JDK import '" + fqn + "' (use java.lang." + simple + ")"));
            }
        }
        for (String line : bogusJdkLines) {
            content = removeImportLine(content, line);
        }

        // ── 0. Remove wrong-package imports of known types ───────────────
        // A KB-known type imported from a package OTHER than its one
        // canonical FQN is a hallucination (e.g.
        // `org.apache.kafka.common.record.OffsetAndMetadata` instead of
        // `...clients.consumer.OffsetAndMetadata`).  Drop it — the canonical
        // import is either already present or gets added in step 2, and the
        // simple-name reference then resolves correctly.  Mechanically
        // certain: the KB defines exactly one valid package per type.
        Matcher wp = IMPORT_LINE.matcher(content);
        List<String> wrongPackageLines = new ArrayList<>();
        while (wp.find()) {
            String fqn = wp.group(1);
            String canonical = knownImports.get(simpleName(fqn));
            if (canonical != null && !canonical.equals(fqn)) {
                wrongPackageLines.add(wp.group().strip());
                actions.add(new RepairAction(RepairAction.Type.REMOVE_WRONG_PACKAGE_IMPORT, path,
                        "removed wrong-package import '" + fqn + "' (canonical is '" + canonical + "')"));
            }
        }
        for (String line : wrongPackageLines) {
            content = removeImportLine(content, line);
        }

        // ── 1. Remove forbidden + unused imports ─────────────────────────
        Matcher im = IMPORT_LINE.matcher(content);
        List<String> linesToRemove = new ArrayList<>();
        while (im.find()) {
            String fqn = im.group(1);
            if (!isForbidden(fqn, forbidden)) continue;
            String simple = simpleName(fqn);
            String bodyWithoutImports = stripImportLines(content);
            if (!usesToken(bodyWithoutImports, simple)) {
                linesToRemove.add(im.group().strip());
                actions.add(new RepairAction(RepairAction.Type.REMOVE_FORBIDDEN_IMPORT, path,
                        "removed unused forbidden import '" + fqn + "'"));
            }
        }
        String working = content;
        for (String line : linesToRemove) {
            working = removeImportLine(working, line);
        }

        // ── 2. Add missing imports for known Kafka + common types ────────
        // Merge: KB-known Kafka types win over the common defaults on a
        // simple-name collision.
        Map<String, String> addable = new LinkedHashMap<>(COMMON_TYPE_IMPORTS);
        addable.putAll(knownImports);
        List<String> toAdd = new ArrayList<>();
        for (Map.Entry<String, String> known : addable.entrySet()) {
            String simple = known.getKey();
            String fqn = known.getValue();
            if (!usesToken(stripImportLines(working), simple)) continue;   // type not used
            if (importsType(working, fqn, simple)) continue;               // already imported (exact or wildcard)
            if (declaresType(working, simple)) continue;                   // local type shadows the name
            toAdd.add(fqn);
        }
        if (!toAdd.isEmpty()) {
            working = insertImports(working, toAdd);
            for (String fqn : toAdd) {
                actions.add(new RepairAction(RepairAction.Type.ADD_MISSING_IMPORT, path,
                        "added import '" + fqn + "'"));
            }
        }
        return working;
    }

    // ── predicates ───────────────────────────────────────────────────────────

    private static boolean isForbidden(String fqn, java.util.Set<String> forbidden) {
        for (String f : forbidden) {
            if (f == null || f.isBlank()) continue;
            if (f.endsWith(".*")) {
                String prefix = f.substring(0, f.length() - 2);
                if (fqn.startsWith(prefix + ".")) return true;
            } else if (fqn.equals(f)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code fqn} is imported explicitly, or via a wildcard of its package. */
    private static boolean importsType(String content, String fqn, String simple) {
        if (content.contains("import " + fqn + ";")
                || content.contains("import static " + fqn + ".")) {
            return true;
        }
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot > 0) {
            String pkg = fqn.substring(0, lastDot);
            if (content.contains("import " + pkg + ".*;")) return true;
        }
        return false;
    }

    private static boolean declaresType(String content, String simple) {
        return Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(simple) + "\\b")
                .matcher(content).find();
    }

    /** Word-boundary token match — the simple type name used somewhere. */
    private static boolean usesToken(String content, String simple) {
        return Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(content).find();
    }

    // ── edits ──────────────────────────────────────────────────────────────────

    /** Content with every import line blanked, so token scans don't see imports. */
    private static String stripImportLines(String content) {
        return IMPORT_LINE.matcher(content).replaceAll("");
    }

    private static String removeImportLine(String content, String importLineTrimmed) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder(content.length());
        boolean removed = false;
        for (String line : lines) {
            if (!removed && line.strip().equals(importLineTrimmed)) {
                removed = true;   // drop only the first matching occurrence
                continue;
            }
            sb.append(line).append('\n');
        }
        // split with -1 keeps a trailing empty element; trim the extra '\n' we added.
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Insert {@code import fqn;} lines after the last existing import, or
     * after the package declaration when there are no imports yet.
     * Imports added in catalogue order, deduped.
     */
    private static String insertImports(String content, List<String> fqns) {
        StringBuilder block = new StringBuilder();
        for (String fqn : fqns) block.append("import ").append(fqn).append(";\n");

        // After the last import.
        Matcher im = IMPORT_LINE.matcher(content);
        int lastImportEnd = -1;
        while (im.find()) lastImportEnd = im.end();
        if (lastImportEnd >= 0) {
            int insertAt = content.indexOf('\n', lastImportEnd);
            if (insertAt < 0) insertAt = content.length();
            else insertAt += 1;
            return content.substring(0, insertAt) + block + content.substring(insertAt);
        }
        // No imports — after the package declaration.
        Matcher pkg = PACKAGE_DECL.matcher(content);
        if (pkg.find()) {
            int insertAt = content.indexOf('\n', pkg.end());
            if (insertAt < 0) insertAt = content.length();
            else insertAt += 1;
            return content.substring(0, insertAt) + "\n" + block + content.substring(insertAt);
        }
        // No package either — prepend.
        return block + content;
    }

    private static String simpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    // ── result types ─────────────────────────────────────────────────────────

    public record RepairResult(Map<String, String> repairedFiles, List<RepairAction> actions)
            implements Serializable {
        public RepairResult {
            repairedFiles = repairedFiles == null ? Map.of() : Map.copyOf(repairedFiles);
            actions       = actions       == null ? List.of() : List.copyOf(actions);
        }
        public boolean changedAnything() { return !actions.isEmpty(); }
    }

    public record RepairAction(Type type, String filePath, String detail) implements Serializable {
        /**
         * True for fixes that neutralise a <b>cascade trigger</b> — a syntax /
         * structural error that, left in place, aborts annotation processing
         * (Lombok) for the whole compile round and inflates the error count
         * 50–200× with phantom "cannot find symbol" failures.  Counted per-run
         * so a compile-error spike can be recognised as cascade noise, not a
         * real quality regression.
         */
        public boolean isCascadeTrigger() {
            return type == Type.REMOVE_INVALID_OBJECT_SUPERTYPE || type == Type.FIX_PACKAGE_DECLARATION;
        }
        public enum Type {
            ADD_MISSING_IMPORT, REMOVE_FORBIDDEN_IMPORT, REMOVE_WRONG_PACKAGE_IMPORT,
            FIX_PACKAGE_PREFIX, REMOVE_BOGUS_JDK_IMPORT, FIX_PACKAGE_DECLARATION,
            STRIP_REDUNDANT_SLF4J, REMOVE_INVALID_OBJECT_SUPERTYPE
        }
    }
}
