package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationDecisionsJsonConverterTest {

    private final MigrationDecisionsJsonConverter converter = new MigrationDecisionsJsonConverter();

    @Test
    void nullAndEmptyRoundTrip() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(converter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
    }

    @Test
    void decisionsRoundTripWithInstant() {
        List<MigrationDecision> original = List.of(
                MigrationDecision.replaceType(
                        "com.google.api.services.pubsub.Pubsub",
                        "KafkaProducer+KafkaConsumer", "client swap"),
                MigrationDecision.replaceApi(
                        "acknowledge", "commitSync",
                        "com.example.PubsubServiceImpl", "ack→commit"),
                MigrationDecision.replaceDependency(
                        "com.google.apis:google-api-services-pubsub",
                        "org.apache.kafka:kafka-clients", "dep swap"));

        String json = converter.convertToDatabaseColumn(original);
        List<MigrationDecision> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).isEqualTo(original);
        // Instant serialised as ISO-8601, not numeric epoch.
        assertThat(json).contains("decidedAt");
        assertThat(json).doesNotContain("\"decidedAt\":1");  // would indicate epoch millis
        // Enum kind round-trips by name.
        assertThat(json).contains("REPLACE_TYPE", "REPLACE_API", "REPLACE_DEPENDENCY");
    }
}
