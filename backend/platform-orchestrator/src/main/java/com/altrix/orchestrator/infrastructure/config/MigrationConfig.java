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
 *     allowed-added-artifacts: kafka-clients, spring-kafka
 * </pre>
 *
 * <p>Add new knobs here as the migrator grows; do not reintroduce private
 * static finals for things callers might want to tune.
 */
@ConfigurationProperties(prefix = "migration")
public record MigrationConfig(
        Pom pom
) {

    public MigrationConfig {
        if (pom == null) pom = new Pom(List.of("kafka-clients", "spring-kafka"));
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
            @DefaultValue({"kafka-clients", "spring-kafka"})
            List<String> allowedAddedArtifacts
    ) {
        public Pom {
            allowedAddedArtifacts = allowedAddedArtifacts != null
                    ? List.copyOf(allowedAddedArtifacts)
                    : List.of("kafka-clients", "spring-kafka");
        }
    }
}
