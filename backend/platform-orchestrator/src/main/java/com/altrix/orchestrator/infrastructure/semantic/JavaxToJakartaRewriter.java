package com.altrix.orchestrator.infrastructure.semantic;

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
 * Deterministic {@code javax.* → jakarta.*} namespace rewriter for the
 * Jakarta EE 9+ "big bang" rename.
 *
 * <p>The free-tier migrator routinely emits the OLD Java EE namespace
 * ({@code import javax.enterprise.context.ApplicationScoped;}) on a project
 * whose classpath only ships the Jakarta artifacts — producing "package
 * javax.enterprise.context does not exist".  Today that self-heals across
 * sandbox retries; this guard fixes it BEFORE the sandbox so it costs zero
 * retries.
 *
 * <p><b>Scope is deliberately the EE namespaces only.</b>  Many {@code javax.*}
 * packages are JDK packages that did NOT move ({@code javax.crypto},
 * {@code javax.net}, {@code javax.sql}, {@code javax.naming}, {@code javax.xml.parsers},
 * {@code javax.management}, {@code javax.transaction.xa},
 * {@code javax.annotation.processing}, …) — rewriting those would BREAK the
 * build.  The {@link #MOVED} / exception logic encodes exactly the Eclipse
 * Transformer mapping subset that is safe.
 *
 * <p><b>Gating:</b> only run when the project actually targets Jakarta
 * ({@link #targetsJakarta}).  On a genuine legacy Java EE ({@code javax})
 * project this rewrite would be wrong, so the caller must check first.
 *
 * <p>Pure + idempotent: running twice yields the same output (a file with
 * no {@code javax.*} EE imports is returned unchanged).
 */
@Slf4j
@Component
public class JavaxToJakartaRewriter {

    /** Any {@code javax.<dotted-path>} occurrence — we decide per-match whether it moved. */
    private static final Pattern JAVAX_FQN = Pattern.compile("\\bjavax\\.([a-zA-Z0-9_.]+)");

    /** Single-segment EE namespaces that moved wholesale to {@code jakarta.*}. */
    private static final List<String> MOVED_FIRST_SEGMENTS = List.of(
            "activation", "batch", "decorator", "ejb", "el", "enterprise", "faces",
            "inject", "interceptor", "jms", "json", "mail", "persistence", "resource",
            "servlet", "validation", "websocket");

    /**
     * Detects whether the project targets Jakarta EE — the gate for
     * {@link #rewrite}.  Any one signal is sufficient:
     * <ul>
     *   <li>the blueprint's framework label says "Jakarta" (most authoritative);</li>
     *   <li>the pom declares a {@code jakarta.*} dependency (e.g.
     *       {@code jakarta.jakartaee-api});</li>
     *   <li>any source already imports a {@code jakarta.*} type — the project
     *       is unambiguously on the new namespace, so any lingering
     *       {@code javax.* EE} import is a migrator slip.</li>
     * </ul>
     */
    public boolean targetsJakarta(Map<String, String> javaFiles, String pomXml, String frameworkLabel) {
        if (frameworkLabel != null && frameworkLabel.toLowerCase().contains("jakarta")) return true;
        if (pomXml != null) {
            String p = pomXml.toLowerCase();
            if (p.contains("jakarta.jakartaee-api") || p.contains("<groupid>jakarta.")) return true;
        }
        if (javaFiles != null) {
            for (String src : javaFiles.values()) {
                if (src != null && src.contains("import jakarta.")) return true;
            }
        }
        return false;
    }

    /**
     * Rewrites every {@code .java} entry, swapping moved {@code javax.* EE}
     * namespaces to {@code jakarta.*}.  Non-Java files pass through.  Caller
     * is responsible for gating on {@link #targetsJakarta}.
     */
    public Result rewrite(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return new Result(files == null ? Map.of() : files, List.of());
        }
        Map<String, String> out = new LinkedHashMap<>(files.size());
        List<String> actions = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || !path.toLowerCase().endsWith(".java") || content == null) {
                out.put(path, content);
                continue;
            }
            String rewritten = rewriteContent(content);
            out.put(path, rewritten);
            if (!rewritten.equals(content)) {
                actions.add(path);
            }
        }
        return new Result(out, actions);
    }

    private String rewriteContent(String content) {
        Matcher m = JAVAX_FQN.matcher(content);
        StringBuilder sb = new StringBuilder(content.length());
        while (m.find()) {
            String path = m.group(1);                 // dotted path after "javax."
            String replacement = movedNamespace(path) ? "jakarta." + path : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * True when {@code javax.<path>} is a Jakarta EE namespace that moved —
     * and NOT one of the JDK exceptions that kept the {@code javax} prefix.
     */
    private static boolean movedNamespace(String path) {
        // ── JDK exceptions inside otherwise-moved namespaces ──
        if (startsWithSegment(path, "transaction.xa")) return false;        // JDK
        if (startsWithSegment(path, "annotation.processing")) return false; // JDK compiler API

        // ── Multi-segment moved namespaces (be specific so we don't touch
        //     JDK siblings like javax.security.auth or javax.xml.parsers) ──
        if (startsWithSegment(path, "ws.rs")) return true;
        if (startsWithSegment(path, "security.auth.message")
                || startsWithSegment(path, "security.enterprise")
                || startsWithSegment(path, "security.jacc")) return true;
        if (startsWithSegment(path, "xml.bind")
                || startsWithSegment(path, "xml.soap")
                || startsWithSegment(path, "xml.ws")) return true;

        // ── transaction / annotation: moved EXCEPT the JDK sub-packages handled above ──
        if (startsWithSegment(path, "transaction") || startsWithSegment(path, "annotation")) return true;

        // ── Single-segment wholesale moves ──
        String first = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        return MOVED_FIRST_SEGMENTS.contains(first);
    }

    /** {@code path} equals {@code seg} or begins with {@code seg + "."}. */
    private static boolean startsWithSegment(String path, String seg) {
        return path.equals(seg) || path.startsWith(seg + ".");
    }

    /** Outcome of a rewrite pass. */
    public record Result(Map<String, String> rewrittenFiles, List<String> changedPaths)
            implements Serializable {
        public Result {
            rewrittenFiles = rewrittenFiles == null ? Map.of() : Map.copyOf(rewrittenFiles);
            changedPaths   = changedPaths   == null ? List.of() : List.copyOf(changedPaths);
        }
        public boolean changedAnything() { return !changedPaths.isEmpty(); }
    }
}
