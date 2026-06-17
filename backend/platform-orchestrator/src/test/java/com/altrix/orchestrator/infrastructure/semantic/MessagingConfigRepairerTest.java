package com.altrix.orchestrator.infrastructure.semantic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingConfigRepairerTest {

    private final MessagingConfigRepairer repairer = new MessagingConfigRepairer();

    @Test
    void correctsMisfiledStringSerializerInYaml() {
        // The exact boot-crash bug: wrong package in application.yml.
        String yml = """
                spring:
                  kafka:
                    producer:
                      key-serializer: org.springframework.kafka.support.serializer.StringSerializer
                      value-serializer: org.springframework.kafka.support.serializer.StringSerializer
                """;
        var result = repairer.repair(Map.of("src/main/resources/application.yml", yml));
        String out = result.repairedFiles().get("src/main/resources/application.yml");
        assertThat(out).doesNotContain("org.springframework.kafka.support.serializer.StringSerializer");
        assertThat(out).contains("org.apache.kafka.common.serialization.StringSerializer");
        assertThat(result.changedAnything()).isTrue();
    }

    @Test
    void correctsSerializerFqnInProperties() {
        String props = "spring.kafka.consumer.value-deserializer="
                + "org.springframework.kafka.support.serializer.LongDeserializer\n";
        var result = repairer.repair(Map.of("application.properties", props));
        assertThat(result.repairedFiles().get("application.properties"))
                .contains("org.apache.kafka.common.serialization.LongDeserializer");
    }

    @Test
    void leavesSpringOwnJsonSerializerUntouched() {
        // JsonSerializer genuinely lives in the Spring package — must NOT be remapped.
        String yml = "value-serializer: org.springframework.kafka.support.serializer.JsonSerializer\n";
        var result = repairer.repair(Map.of("application.yml", yml));
        assertThat(result.repairedFiles().get("application.yml"))
                .isEqualTo(yml);
        assertThat(result.changedAnything()).isFalse();
    }

    @Test
    void injectsAckModeManualWhenConsumerUsesAcknowledgment() {
        // A manual-ack consumer + a yml without ack-mode → must inject it,
        // else the listener throws "No Acknowledgment available" at runtime.
        String subscriber = "package p;\nimport org.springframework.kafka.support.Acknowledgment;\n"
                + "public class Sub { void h(String m, Acknowledgment ack) { ack.acknowledge(); } }";
        String yml = "spring:\n  kafka:\n    bootstrap-servers: localhost:9092\n";
        var result = repairer.repair(Map.of("p/Sub.java", subscriber, "application.yml", yml));
        String outYml = result.repairedFiles().get("application.yml");
        assertThat(outYml).contains("listener:");
        assertThat(outYml).contains("ack-mode: manual");
    }

    @Test
    void doesNotDuplicateAckModeWhenAlreadyPresent() {
        String subscriber = "package p;\npublic class Sub { Object ack; void h(org.springframework.kafka.support.Acknowledgment a){} }";
        String yml = "spring:\n  kafka:\n    listener:\n      ack-mode: manual\n";
        var result = repairer.repair(Map.of("p/Sub.java", subscriber, "application.yml", yml));
        assertThat(result.repairedFiles().get("application.yml")).isEqualTo(yml);
    }

    @Test
    void doesNotInjectAckModeWhenNoManualAckConsumer() {
        String yml = "spring:\n  kafka:\n    bootstrap-servers: localhost:9092\n";
        var result = repairer.repair(Map.of("application.yml", yml));
        assertThat(result.repairedFiles().get("application.yml")).isEqualTo(yml);
    }

    @Test
    void ignoresNonConfigFiles() {
        var result = repairer.repair(Map.of("README.md",
                "org.springframework.kafka.support.serializer.StringSerializer"));
        assertThat(result.changedAnything()).isFalse();
    }
}
