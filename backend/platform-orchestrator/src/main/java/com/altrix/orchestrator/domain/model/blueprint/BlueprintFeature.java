package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.List;

/**
 * A migration-relevant feature detected inside a single source file.
 *
 * <p>Concrete examples for the GCP Pub/Sub → Kafka migration:
 * <ul>
 *   <li>{@code pubsub.publish-single} — caller invokes {@code Pubsub.projects().topics().publish(...)} for one message</li>
 *   <li>{@code pubsub.publish-batch}  — same call inside a loop or via a wrapper splitter</li>
 *   <li>{@code pubsub.pull-ack}       — pair of {@code pull(...)} + {@code acknowledge(...)} calls</li>
 *   <li>{@code pubsub.push-endpoint}  — REST endpoint receiving Pub/Sub push delivery</li>
 *   <li>{@code pubsub.test-iam}       — IAM permission test (no clean Kafka equivalent)</li>
 *   <li>{@code pubsub.ordering-key}   — uses {@code PubsubMessage.setOrderingKey}</li>
 * </ul>
 *
 * @param id           stable feature identifier; namespaced so the same id
 *                     can carry forward across runs and across projects.
 * @param description  one-line human summary shown in the UI.
 * @param evidence     where in the file the feature was detected — usually
 *                     a method + line number captured by the LST walker.
 * @param kafkaTarget  the Kafka API surface the migrator should produce
 *                     in place of this feature.  Free text consumed
 *                     directly by the per-file LLM prompt.  Null when no
 *                     clean equivalent exists (e.g. IAM perm test).
 * @param docRefs      logical paths into the documentation corpus whose
 *                     chunks should be included in the migrator's prompt
 *                     when rewriting this feature.  Resolved later by
 *                     {@code EmbeddingStorePort.findRelevant(...)}
 *                     constrained to these logical paths.
 */
public record BlueprintFeature(
        String id,
        String description,
        Evidence evidence,
        String kafkaTarget,
        List<String> docRefs
) implements Serializable {

    public BlueprintFeature {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        docRefs = docRefs == null ? List.of() : List.copyOf(docRefs);
    }

    /**
     * Pointer to the source location that triggered detection.
     *
     * @param method  enclosing method (or class for class-level evidence)
     * @param line    1-based source line; -1 when unknown
     * @param snippet up to ~80 chars of source for the UI to display;
     *                informational only, never parsed.
     */
    public record Evidence(String method, int line, String snippet) implements Serializable {
        public Evidence {
            if (snippet != null && snippet.length() > 200) {
                snippet = snippet.substring(0, 200);
            }
        }
    }
}
