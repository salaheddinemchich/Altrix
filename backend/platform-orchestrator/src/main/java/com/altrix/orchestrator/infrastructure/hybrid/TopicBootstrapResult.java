package com.altrix.orchestrator.infrastructure.hybrid;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Outcome of {@link TopicBootstrapAnchor#analyze}: the set of topic constant
 * <em>simple names</em> a Jakarta EE bootstrap class declared it creates (e.g.
 * {@code ORDERS_TOPIC}, {@code PAYMENTS_COMPLETED_TOPIC}), plus the source path
 * of that bootstrap class so the migrator can DELETE it (a manually-bootstrapped
 * Spring context with {@code KafkaAdmin}+{@code NewTopic} beans replaces it — see
 * {@link HybridScaffoldingGenerator}).
 *
 * <p>{@link #topicConstantNames()} preserves source-encounter order so the
 * generated {@code NewTopic} beans render deterministically.
 */
public record TopicBootstrapResult(Set<String> topicConstantNames, String sourceFilePath) {

    public TopicBootstrapResult {
        // Preserve insertion order (Set.copyOf would not) and make immutable.
        topicConstantNames = topicConstantNames != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(topicConstantNames))
                : Set.of();
    }
}
