package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Structured Pub/Sub → Kafka migration knowledge base.
 *
 * <p>The single source of truth for "how does Pub/Sub feature X become
 * Kafka", consumed by:
 * <ul>
 *   <li>the migrator's per-file prompt — what Kafka API to emit;</li>
 *   <li>the Kafka-API validator — what symbols are forbidden;</li>
 *   <li>the dependency validator — which Maven dep provides a class;</li>
 *   <li>the deterministic repair engine — known FQN + import + dep fixes.</li>
 * </ul>
 *
 * <p>YAML-configured (loaded from {@code kafka-migration-kb.yml} via
 * {@code spring.config.import} in application.yml) so the catalog grows
 * without recompiles — same principle as {@link DocumentationCorpusConfig}.
 *
 * <p>Eventually consolidates the scattered hard-coded lists:
 * {@code MigrationConfig.source.deniedImports} and the inline replacement
 * suggestions inside {@code PubSubLeakValidator}.  Those are migrated INTO
 * this catalog; the de-duplication (removing the originals) happens in a
 * later stage once the SemanticValidator phase reads exclusively from here.
 *
 * <pre>{@code
 * kafka-migration:
 *   global-forbidden-imports:
 *     - org.apache.kafka.clients.consumer.OffsetCommitResult
 *     - org.apache.kafka.common.security.auth.permission.*
 *   class-dependencies:
 *     - class-fqn: org.apache.kafka.clients.consumer.KafkaConsumer
 *       dependency: org.apache.kafka:kafka-clients
 *   mappings:
 *     - pubsub-feature: pubsub.ack
 *       kafka-equivalent: "consumer.commitSync()"
 *       required-classes: [org.apache.kafka.clients.consumer.OffsetAndMetadata]
 *       required-imports: [org.apache.kafka.clients.consumer.KafkaConsumer]
 *       required-dependencies: ["org.apache.kafka:kafka-clients"]
 *       forbidden-symbols: [OffsetCommitResult]
 *       notes: "Kafka commits offsets, not per-message acks"
 * }</pre>
 */
@ConfigurationProperties(prefix = "kafka-migration")
public record KafkaMigrationKnowledgeBase(
        List<Mapping> mappings,
        List<ClassDependency> classDependencies,
        @DefaultValue({}) List<String> globalForbiddenImports
) {

    public KafkaMigrationKnowledgeBase {
        mappings               = mappings               != null ? List.copyOf(mappings)               : List.of();
        classDependencies      = classDependencies      != null ? List.copyOf(classDependencies)      : List.of();
        globalForbiddenImports = globalForbiddenImports != null ? List.copyOf(globalForbiddenImports) : List.of();
    }

    /**
     * One Pub/Sub-feature → Kafka mapping.
     *
     * @param pubsubFeature        feature id matching the blueprint's
     *                             {@code BlueprintFeature.id} (e.g.
     *                             {@code "pubsub.ack"}, {@code "pubsub.publish-batch"}).
     * @param kafkaEquivalent      the Kafka call/idiom the migrator should
     *                             produce.  Fed verbatim into the prompt.
     * @param requiredClasses      FQNs that MUST resolve in the migrated
     *                             output for this feature — the import
     *                             resolver checks these exist on the classpath.
     * @param requiredImports      import statements the migrated file needs.
     * @param requiredDependencies Maven coordinates ({@code group:artifact})
     *                             that must be on the build for the classes
     *                             to resolve.
     * @param forbiddenSymbols     simple names / FQNs the model is known to
     *                             hallucinate for this feature — flagged by
     *                             the Kafka-API validator.
     * @param notes                free-text guidance shown to the model.
     */
    public record Mapping(
            String pubsubFeature,
            String kafkaEquivalent,
            @DefaultValue({}) List<String> requiredClasses,
            @DefaultValue({}) List<String> requiredImports,
            @DefaultValue({}) List<String> requiredDependencies,
            @DefaultValue({}) List<String> forbiddenSymbols,
            String notes
    ) {
        public Mapping {
            if (pubsubFeature == null || pubsubFeature.isBlank()) {
                throw new IllegalArgumentException("Mapping.pubsubFeature must not be blank");
            }
            requiredClasses      = requiredClasses      != null ? List.copyOf(requiredClasses)      : List.of();
            requiredImports      = requiredImports      != null ? List.copyOf(requiredImports)      : List.of();
            requiredDependencies = requiredDependencies != null ? List.copyOf(requiredDependencies) : List.of();
            forbiddenSymbols     = forbiddenSymbols     != null ? List.copyOf(forbiddenSymbols)     : List.of();
        }
    }

    /**
     * Maps a fully-qualified class name to the Maven dependency that
     * provides it.  Used by the dependency validator: "the code uses
     * {@code org.apache.kafka.clients.consumer.KafkaConsumer} but the pom
     * has no {@code org.apache.kafka:kafka-clients} → MissingDependencyIssue".
     *
     * @param classFqn   fully-qualified class name (or a package prefix
     *                   ending in {@code .*} to cover a whole package).
     * @param dependency Maven coordinate {@code group:artifact} (version
     *                   omitted — version policy is the pom's concern).
     */
    public record ClassDependency(String classFqn, String dependency) {
        public ClassDependency {
            if (classFqn == null || classFqn.isBlank())     throw new IllegalArgumentException("classFqn required");
            if (dependency == null || dependency.isBlank()) throw new IllegalArgumentException("dependency required");
        }
    }

    // ── Query helpers ───────────────────────────────────────────────────────

    /** The mapping for a blueprint feature id, or empty when uncatalogued. */
    public Optional<Mapping> findByFeature(String featureId) {
        if (featureId == null) return Optional.empty();
        return mappings.stream().filter(m -> featureId.equals(m.pubsubFeature())).findFirst();
    }

    /**
     * Every forbidden import/symbol the migrated code must NOT contain —
     * the union of {@link #globalForbiddenImports()} and every mapping's
     * {@link Mapping#forbiddenSymbols()}.  Entries ending in {@code .*}
     * are package wildcards (the validator honours the same wildcard
     * semantics as {@code CoreMigratorAgent.firstDeniedImport}).
     */
    public Set<String> allForbiddenImports() {
        Set<String> all = new LinkedHashSet<>(globalForbiddenImports);
        for (Mapping m : mappings) all.addAll(m.forbiddenSymbols());
        return all;
    }

    /**
     * The Maven dependency that provides {@code classFqn}, or empty when
     * the class isn't catalogued.  Honours {@code .*} package-prefix
     * entries in the config.
     */
    public Optional<String> dependencyForClass(String classFqn) {
        if (classFqn == null) return Optional.empty();
        for (ClassDependency cd : classDependencies) {
            String key = cd.classFqn();
            if (key.endsWith(".*")) {
                String prefix = key.substring(0, key.length() - 2);
                if (classFqn.startsWith(prefix + ".")) return Optional.of(cd.dependency());
            } else if (key.equals(classFqn)) {
                return Optional.of(cd.dependency());
            }
        }
        return Optional.empty();
    }

    /**
     * All Maven coordinates the catalogued Kafka migration may require —
     * the union across every mapping.  Used by the dependency validator
     * as the "candidate adds" set.
     */
    public Set<String> allRequiredDependencies() {
        Set<String> deps = new LinkedHashSet<>();
        for (Mapping m : mappings) deps.addAll(m.requiredDependencies());
        return deps;
    }

    /**
     * Simple type name → fully-qualified import, built from every
     * non-wildcard FQN in the mappings' {@code required-imports} and
     * {@code required-classes}.  The deterministic repair engine uses this
     * to add a missing import when a known Kafka type is referenced but
     * not imported (e.g. {@code KafkaConsumer} used →
     * {@code import org.apache.kafka.clients.consumer.KafkaConsumer;}).
     *
     * <p>First definition wins on a simple-name collision (the catalogue
     * is curated so this shouldn't happen for Kafka types).  Insertion
     * order preserved for stable output.
     */
    public Map<String, String> knownTypeImports() {
        Map<String, String> index = new LinkedHashMap<>();
        for (Mapping m : mappings) {
            addFqns(index, m.requiredImports());
            addFqns(index, m.requiredClasses());
        }
        return index;
    }

    private static void addFqns(Map<String, String> index, List<String> fqns) {
        for (String fqn : fqns) {
            if (fqn == null || fqn.isBlank() || fqn.endsWith(".*")) continue;
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot <= 0 || lastDot == fqn.length() - 1) continue;
            String simple = fqn.substring(lastDot + 1);
            index.putIfAbsent(simple, fqn);
        }
    }
}
