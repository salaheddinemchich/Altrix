package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.domain.port.out.SandboxRunnerPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Second {@link SandboxRunnerPort} (#97) — pattern-based static analysis
 * tuned specifically to the way the migrator AI tends to mis-rewrite code.
 *
 * <p>The full {@link StaticSandboxRunner} catches structural regressions
 * (residual Pub/Sub imports, empty files); this runner catches the
 * <em>soft</em> regressions that still let a project compile but are
 * almost certainly wrong:
 *
 * <ul>
 *   <li>AI commentary left in source — markers like
 *       {@code TODO: implement}, {@code // ...rest of method},
 *       {@code /* Add your logic here *}{@code /} are dead giveaways the
 *       AI scaffolded but didn't finish a method.</li>
 *   <li>Empty migrated method bodies — a {@code public void onMessage(...) { }}
 *       in a file that previously had a real implementation strongly
 *       suggests the AI dropped logic on the floor.</li>
 *   <li>Unconverted exception types in catch / throws clauses
 *       ({@code PubSubException}, {@code PubsubException}) — the runtime
 *       no longer throws these; catching them is dead code at best,
 *       a swallowed Kafka exception at worst.</li>
 *   <li>Log messages still mentioning Pub/Sub — the migrator renames
 *       the API but routinely forgets the log string, leaving
 *       {@code log.info("Publishing to Pub/Sub: " + topic)} pointing
 *       to Kafka.  WARNING severity (cosmetic, not a build break).</li>
 * </ul>
 *
 * <p>Order 1 — runs after StaticSandboxRunner (order 0) but before any
 * future Docker / Checkstyle / SpotBugs runners.
 *
 * <p>SpotBugs proper and a Checkstyle XML-config runner are deferred to
 * #16/#17 when a real sandbox compile produces .class files for SpotBugs
 * and decisions are made about which Checkstyle rule sets to enforce.
 */
@Component
public class MigrationQualityRunner implements SandboxRunnerPort {

    public static final String ID = "migration-quality";

    /** Regexes are pre-compiled — the validator runs them on every MODIFIED file. */
    private static final Pattern AI_COMMENTARY = Pattern.compile(
            "//\\s*(TODO|FIXME|XXX|HACK)[:\\s]" +
            "|//\\s*\\.\\.\\.(rest|implementation|your code|continue|etc)" +
            "|/\\*\\s*(Add|Implement|Insert|Place)\\s+your" +
            "|//\\s*Original (Pub/Sub|implementation|code)" +
            "|//\\s*(MANUAL POLLING|RECOMMENDED APPROACH|TO ACTIVATE)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Detects empty method bodies: a non-{@code interface} declaration whose
     * body is just whitespace.  Deliberately conservative — only matches
     * bodies inside a top-level class context, not abstract / interface
     * methods.  Multi-line aware via DOTALL on the body regex.
     */
    private static final Pattern EMPTY_METHOD_BODY = Pattern.compile(
            // method signature with parens, optional throws, then { whitespace-or-comment-only }
            "(public|private|protected)\\s+[\\w<>,\\s\\[\\]]+\\s+\\w+\\s*\\([^)]*\\)\\s*" +
            "(throws\\s+[\\w.,\\s]+)?\\s*\\{\\s*(/\\*[^*]*\\*/|//[^\\n]*\\n|\\s)*\\}");

    private static final Pattern PUBSUB_EXCEPTION = Pattern.compile(
            "\\b(catch\\s*\\(|throws\\s+[\\w.,\\s]*)\\bPub[Ss]ubException\\b");

    private static final Pattern PUBSUB_IN_LOG = Pattern.compile(
            "(log|logger|LOG|LOGGER)\\.(trace|debug|info|warn|error)\\s*\\(\\s*\"[^\"]*Pub[/-]?Sub[^\"]*\"");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 1;   // after StaticSandboxRunner(0), before Docker runner(10+)
    }

    @Override
    public List<SandboxFinding> run(MigrationArtifact artifact) {
        if (artifact == null || artifact.files() == null) return List.of();

        List<SandboxFinding> findings = new ArrayList<>();
        for (var file : artifact.files()) {
            // Migration-quality checks only apply to .java files we just rewrote.
            // pom.xml / yaml are checked by StaticSandboxRunner; AI commentary
            // in a comment block there is fine.
            if (file.changeType() != FileChangeType.MODIFIED) continue;
            String path = file.newPath();
            if (path == null || !path.endsWith(".java")) continue;

            String content = file.content();
            if (content == null || content.isEmpty()) continue;

            if (AI_COMMENTARY.matcher(content).find()) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "AI scaffolding marker left in source (TODO / 'add your...' / 'rest of...') "
                        + "— the rewrite is incomplete"));
            }
            if (EMPTY_METHOD_BODY.matcher(content).find()) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "Empty method body in migrated code — the original implementation was likely dropped"));
            }
            if (PUBSUB_EXCEPTION.matcher(content).find()) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "PubSubException referenced in catch / throws — Kafka does not throw this; "
                        + "convert to KafkaException or remove"));
            }
            if (PUBSUB_IN_LOG.matcher(content).find()) {
                findings.add(SandboxFinding.ofFile(ID, Severity.WARNING, path,
                        "Log message still mentions Pub/Sub after migration — update for accuracy"));
            }
        }
        return findings;
    }
}
