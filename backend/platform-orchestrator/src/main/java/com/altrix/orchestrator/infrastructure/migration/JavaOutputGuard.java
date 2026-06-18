package com.altrix.orchestrator.infrastructure.migration;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

/**
 * Shared defence against malformed AI output in Java source files.
 *
 * <p>{@code CoreMigratorAgent}'s length-ratio truncation check
 * ({@code looksTruncated}) only fires when the output is under 30% of the
 * original size — it misses truncation near the end of a large file (e.g.
 * losing the last one or two methods of a 200-line class) and it has no
 * signal at all for mid-stream corruption that leaves the file the same
 * length but with garbled tokens (a field declaration cut down to a
 * fragment, a string literal split in two). Both are exactly the shapes an
 * LLM produces when it hits its token limit mid-response or glitches during
 * generation.
 *
 * <p>Parsing with the same JavaParser the contract validator already
 * depends on catches every one of these cases in one check: a genuinely
 * complete, well-formed Java file always parses; a truncated, garbled, or
 * otherwise corrupted one never does. Used by every AI-repair path that
 * accepts a rewritten Java file (the core migrator, the contract repairer,
 * the Pub/Sub leak repairer) so corrupted output is rejected at the same
 * point regardless of which pass produced it.
 */
public final class JavaOutputGuard {

    private JavaOutputGuard() {
    }

    /** @return {@code true} if {@code java} is NOT valid, parseable Java source. */
    public static boolean isMalformed(String java) {
        if (java == null || java.isBlank()) return true;
        try {
            ParseResult<CompilationUnit> result = new JavaParser().parse(java);
            return !result.isSuccessful();
        } catch (Exception e) {
            return true;
        }
    }
}
