package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.List;

/**
 * Per-file slice of the {@link ProjectBlueprint}.  This is what the
 * migrator reads when rewriting one file — everything it needs to
 * decide what to change is on this record (the LLM prompt builder
 * does NOT walk the whole blueprint per call).
 *
 * @param path           repository-relative path; matches the
 *                       {@code MigratedFile.originalPath} the migrator
 *                       receives.  Stable, never null.
 * @param simpleName     the public top-level type's simple name (or the
 *                       file's basename when no type exists, e.g. for
 *                       {@code pom.xml}).
 * @param kind           type kind for Java files; null for non-Java.
 * @param packageName    full dotted package, or null when not applicable.
 * @param role           short semantic label inferred from the
 *                       relationships + features — "MessagingAdapter",
 *                       "EJB Consumer Poller", "REST Resource", "Producer
 *                       CDI Bean", "Domain Aggregate".  Free text, fed
 *                       to the migrator prompt verbatim.
 * @param relationships  the file's immediate type-graph neighbours
 *                       (see {@link BlueprintRelationships}).
 * @param features       migration-relevant features detected inside this
 *                       file.  Empty list means the file is pass-through
 *                       (no Pub/Sub semantics → migrator can leave it
 *                       alone).
 * @param migrationNotes free-text guidance the LST walker / feature
 *                       classifier emit for the migrator (e.g. "preserve
 *                       @Vetoed, replace pull/ack pair with poll/commitSync,
 *                       keep @Schedule cadence").  Null when no guidance.
 */
public record BlueprintFile(
        String path,
        String simpleName,
        Kind kind,
        String packageName,
        String role,
        BlueprintRelationships relationships,
        List<BlueprintFeature> features,
        String migrationNotes
) implements Serializable {

    public BlueprintFile {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path required");
        relationships = relationships == null ? BlueprintRelationships.empty() : relationships;
        features      = features      == null ? List.of() : List.copyOf(features);
    }

    public enum Kind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, OTHER
    }

    /**
     * Convenience — true when the file declares no migration-relevant features.
     *
     * <p>Not a serialised property: the JSONB converter disables getter /
     * is-getter auto-detection so records persist by their declared
     * components only, keeping this computed accessor out of the JSON.
     */
    public boolean isPassThrough() {
        return features.isEmpty();
    }
}
