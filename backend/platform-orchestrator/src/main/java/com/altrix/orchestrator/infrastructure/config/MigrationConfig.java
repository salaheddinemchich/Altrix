package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Migration-time knobs for {@code CoreMigratorAgent} and the surrounding
 * pipeline.  Externalised so the user can tune behavior without code changes
 * — values that are not safe to hard-code (per the project's "no magic
 * constants" rule).
 *
 * <pre>
 * migration:
 *   pom:
 *     # Maven artifactIds the migrator is allowed to ADD to a project's
 *     # pom.xml that were not present in the original.  Anything else the
 *     # AI inserts is treated as a hallucination and stripped.
 *     allowed-added-artifacts: kafka-clients, spring-kafka, spring-context
 *   source:
 *     # Fully-qualified Java imports the model is KNOWN to hallucinate.
 *     # When a migrated .java contains any of these imports the file is
 *     # rejected and the original kept — the build then succeeds (with
 *     # the old code in that file) instead of failing with
 *     # "cannot find symbol" on a non-existent Kafka type.  Extend the
 *     # list as new hallucinations are discovered.
 *     denied-imports:
 *       - org.apache.kafka.clients.consumer.OffsetCommitResult
 *       - org.apache.kafka.common.errors.KafkaException     # real one lives at org.apache.kafka.common.KafkaException
 * </pre>
 *
 * <p>Add new knobs here as the migrator grows; do not reintroduce private
 * static finals for things callers might want to tune.
 */
@ConfigurationProperties(prefix = "migration")
public record MigrationConfig(
        Pom pom,
        Source source
) {

    public MigrationConfig {
        if (pom == null)    pom    = new Pom(null);
        if (source == null) source = new Source(null);
    }

    /**
     * POM-specific guards applied to the migrator's output before it is
     * staged for the sandbox compile.
     *
     * @param allowedAddedArtifacts artifactIds the migrator may add even if
     *                              they were not in the original pom (the
     *                              Pub/Sub → Kafka swap target).  Any other
     *                              new dependency is rejected as a
     *                              hallucination and removed.
     */
    public record Pom(
            @DefaultValue({"kafka-clients", "spring-kafka", "spring-context"})
            List<String> allowedAddedArtifacts
    ) {
        public Pom {
            allowedAddedArtifacts = allowedAddedArtifacts != null
                    ? List.copyOf(allowedAddedArtifacts)
                    : List.of("kafka-clients", "spring-kafka", "spring-context");
        }
    }

    /**
     * Per-source-file guards.
     *
     * @param deniedImports fully-qualified imports the model is known to
     *                      invent.  Matching files are reverted to the
     *                      original.  Default seeded with the patterns we
     *                      have observed in real migrations; users can
     *                      extend via YAML / env without code changes.
     */
    public record Source(
            @DefaultValue({
                    // Specific FQNs the model invents
                    "org.apache.kafka.clients.consumer.OffsetCommitResult",
                    "org.apache.kafka.clients.consumer.ConsumerException",
                    // Wrong package — real KafkaException lives at org.apache.kafka.common.KafkaException
                    "org.apache.kafka.common.errors.KafkaException",
                    // Whole bogus packages the model invents (mapping Pub/Sub IAM permissions
                    // to a non-existent Kafka "security.auth.permission" namespace).
                    // Trailing ".*" matches any class under that package.
                    "org.apache.kafka.common.security.auth.permission.*",
                    // Wrong CDI package — real @Produces lives at jakarta.enterprise.inject.Produces.
                    // The model occasionally collapses it into jakarta.inject because @Inject
                    // lives there, but @Produces does not.
                    "jakarta.inject.Produces"
            })
            List<String> deniedImports
    ) {
        public Source {
            deniedImports = deniedImports != null
                    ? List.copyOf(deniedImports)
                    : List.of(
                            "org.apache.kafka.clients.consumer.OffsetCommitResult",
                            "org.apache.kafka.clients.consumer.ConsumerException",
                            "org.apache.kafka.common.errors.KafkaException",
                            "org.apache.kafka.common.security.auth.permission.*",
                            "jakarta.inject.Produces");
        }
    }
}
