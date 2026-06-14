package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Top-level project map produced by {@code ProjectMapperAgent} (the
 * combined Index + Analyse phase).
 *
 * <p>Persisted as one JSONB row per workflow session in {@code project_blueprints}
 * (Flyway V23).  Consumed by:
 * <ul>
 *   <li>{@code MigrationPlannerAgent} — for stack-aware planning</li>
 *   <li>{@code CoreMigratorAgent} — for per-file slice + neighbour info</li>
 *   <li>The Project Map UI panel</li>
 * </ul>
 *
 * @param projectId           the workflow's owning project id.
 * @param sessionId           the workflow-session id that produced this
 *                            blueprint; primary key in the DB.
 * @param generatedAt         when {@code ProjectMapperAgent} finished.
 * @param schemaVersion       JSON schema version — bump when the
 *                            aggregate shape changes incompatibly.
 * @param detectedStack       top-level architecture facts.
 * @param detectedIntegrations external integrations the project uses
 *                            (Pub/Sub, JMS, …).
 * @param files               every indexed source file as a
 *                            {@link BlueprintFile} slice.  This is the
 *                            primary input to the migrator's per-file
 *                            prompt builder — read one entry per
 *                            rewrite call rather than walking the whole
 *                            list.
 * @param semanticGraph       LST-derived class / method / call /
 *                            inheritance / import graph.
 * @param docReferences       pointers to documentation pages this
 *                            blueprint's run fetched + indexed.
 * @param migrationOrder      topologically-sorted file paths (leaves
 *                            first) so when the migrator rewrites a
 *                            class its dependencies are already
 *                            migrated and new types resolve.
 * @param riskNotes           free-text warnings the LST walker /
 *                            feature classifier emit — used by the UI
 *                            risk banner and by the migrator prompt as
 *                            soft hints.
 */
public record ProjectBlueprint(
        String projectId,
        String sessionId,
        Instant generatedAt,
        int schemaVersion,
        DetectedStack detectedStack,
        List<DetectedIntegration> detectedIntegrations,
        List<BlueprintFile> files,
        SemanticGraph semanticGraph,
        List<DocReference> docReferences,
        List<String> migrationOrder,
        List<String> riskNotes
) implements Serializable {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ProjectBlueprint {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId required");
        if (generatedAt == null) generatedAt = Instant.now();
        if (schemaVersion <= 0) schemaVersion = CURRENT_SCHEMA_VERSION;
        if (detectedStack == null) detectedStack = DetectedStack.unknown();
        detectedIntegrations = detectedIntegrations == null ? List.of() : List.copyOf(detectedIntegrations);
        files                = files                == null ? List.of() : List.copyOf(files);
        if (semanticGraph == null) semanticGraph = SemanticGraph.empty();
        docReferences  = docReferences  == null ? List.of() : List.copyOf(docReferences);
        migrationOrder = migrationOrder == null ? List.of() : List.copyOf(migrationOrder);
        riskNotes      = riskNotes      == null ? List.of() : List.copyOf(riskNotes);
    }

    /**
     * Per-file slice lookup — the migrator's primary read pattern.
     * Returns the {@link BlueprintFile} whose {@code path} matches
     * exactly, or empty when the file wasn't part of the blueprint (e.g.
     * a non-Java resource the mapper skipped).
     */
    public Optional<BlueprintFile> fileSlice(String path) {
        if (path == null) return Optional.empty();
        return files.stream().filter(f -> path.equals(f.path())).findFirst();
    }

    /** Count of files that carry at least one migration-relevant feature. */
    public long migrationRelevantFileCount() {
        return files.stream().filter(f -> !f.isPassThrough()).count();
    }
}
