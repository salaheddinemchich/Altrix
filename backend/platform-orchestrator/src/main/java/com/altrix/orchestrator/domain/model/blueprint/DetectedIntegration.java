package com.altrix.orchestrator.domain.model.blueprint;

import java.io.Serializable;
import java.util.List;

/**
 * An external integration the project uses — the source side of the
 * migration target.  For the current GCP Pub/Sub → Kafka migration the
 * only meaningful integration is some flavour of Pub/Sub; the model is
 * designed to grow to other integrations (RabbitMQ, JMS, SQS) without
 * schema changes.
 *
 * @param name       canonical integration name (e.g.
 *                   {@code "GCP Pub/Sub REST v1"}, {@code "Spring Cloud GCP Pub/Sub"}).
 * @param category   coarse bucket: {@code "messaging"}, {@code "database"}, …
 * @param evidence   repository-relative file paths where the integration
 *                   was detected (typically the file that imports the
 *                   integration's client + any direct subclass).
 */
public record DetectedIntegration(
        String name,
        String category,
        List<String> evidence
) implements Serializable {

    public DetectedIntegration {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
