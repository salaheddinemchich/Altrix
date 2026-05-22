package com.altrix.orchestrator.domain.port.out;

import java.util.Optional;

/**
 * Driven port — caches Agent 3 (CoreMigrator) per-file LLM outputs (#28).
 *
 * <p>The migrator is by far the most expensive agent: one LLM call per
 * modified file, each one a large-context heavy-model call.  During
 * iterative development the same project gets migrated repeatedly with
 * no source changes — every run currently pays the full token bill.
 *
 * <p>Cache key is {@code SHA-256(systemPromptHash + path + contentHash)}
 * — included so a prompt tweak forces a fresh call (the migrated output
 * is a function of both the source and the prompt).
 *
 * <p>Implementations must be best-effort: any Redis / store failure must
 * fall through to a live AI call rather than throw.
 */
public interface FileMigrationCachePort {

    /** Returns the cached migrated content for the given key, or empty on miss. */
    Optional<String> get(String cacheKey);

    /** Stores migrated content under the given key.  Best-effort — never throws. */
    void put(String cacheKey, String migratedContent);
}
