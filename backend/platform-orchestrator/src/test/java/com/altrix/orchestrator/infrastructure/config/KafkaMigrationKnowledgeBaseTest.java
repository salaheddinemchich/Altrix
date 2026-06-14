package com.altrix.orchestrator.infrastructure.config;

import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.ClassDependency;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.Mapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaMigrationKnowledgeBaseTest {

    private KafkaMigrationKnowledgeBase kb(List<Mapping> mappings,
                                          List<ClassDependency> deps,
                                          List<String> globalForbidden) {
        return new KafkaMigrationKnowledgeBase(mappings, deps, globalForbidden);
    }

    private Mapping ackMapping() {
        return new Mapping(
                "pubsub.ack", "consumer.commitSync()",
                List.of("org.apache.kafka.clients.consumer.OffsetAndMetadata"),
                List.of("org.apache.kafka.clients.consumer.KafkaConsumer"),
                List.of("org.apache.kafka:kafka-clients"),
                List.of("AcknowledgeRequest", "OffsetCommitResult"),
                "Kafka commits offsets, not per-message acks.");
    }

    @Test
    void nullCollectionsNormaliseToEmpty() {
        var k = new KafkaMigrationKnowledgeBase(null, null, null);
        assertThat(k.mappings()).isEmpty();
        assertThat(k.classDependencies()).isEmpty();
        assertThat(k.globalForbiddenImports()).isEmpty();
    }

    @Test
    void findByFeatureHitsAndMisses() {
        var k = kb(List.of(ackMapping()), List.of(), List.of());
        assertThat(k.findByFeature("pubsub.ack")).isPresent();
        assertThat(k.findByFeature("pubsub.ack").orElseThrow().kafkaEquivalent())
                .isEqualTo("consumer.commitSync()");
        assertThat(k.findByFeature("pubsub.nonexistent")).isEmpty();
        assertThat(k.findByFeature(null)).isEmpty();
    }

    @Test
    void allForbiddenImportsUnionsGlobalAndPerMapping() {
        var k = kb(
                List.of(ackMapping()),
                List.of(),
                List.of("org.apache.kafka.common.security.auth.permission.*"));
        assertThat(k.allForbiddenImports()).contains(
                "org.apache.kafka.common.security.auth.permission.*",  // global
                "AcknowledgeRequest",                                  // per-mapping
                "OffsetCommitResult");                                 // per-mapping
    }

    @Test
    void dependencyForClassExactMatch() {
        var k = kb(List.of(),
                List.of(new ClassDependency(
                        "org.apache.kafka.clients.consumer.KafkaConsumer",
                        "org.apache.kafka:kafka-clients")),
                List.of());
        assertThat(k.dependencyForClass("org.apache.kafka.clients.consumer.KafkaConsumer"))
                .contains("org.apache.kafka:kafka-clients");
        assertThat(k.dependencyForClass("java.lang.String")).isEmpty();
    }

    @Test
    void dependencyForClassWildcardMatchesPackage() {
        var k = kb(List.of(),
                List.of(new ClassDependency(
                        "org.apache.kafka.clients.producer.*",
                        "org.apache.kafka:kafka-clients")),
                List.of());
        assertThat(k.dependencyForClass("org.apache.kafka.clients.producer.KafkaProducer"))
                .contains("org.apache.kafka:kafka-clients");
        assertThat(k.dependencyForClass("org.apache.kafka.clients.producer.ProducerRecord"))
                .contains("org.apache.kafka:kafka-clients");
        // Sibling package must NOT match the producer wildcard.
        assertThat(k.dependencyForClass("org.apache.kafka.clients.consumer.KafkaConsumer"))
                .isEmpty();
    }

    @Test
    void allRequiredDependenciesUnionsAcrossMappings() {
        Mapping publish = new Mapping(
                "pubsub.publish-single", "producer.send(...)",
                List.of(), List.of(),
                List.of("org.apache.kafka:kafka-clients"),
                List.of(), null);
        Mapping spring = new Mapping(
                "pubsub.spring", "kafkaTemplate.send(...)",
                List.of(), List.of(),
                List.of("org.springframework.kafka:spring-kafka"),
                List.of(), null);
        var k = kb(List.of(publish, spring), List.of(), List.of());
        assertThat(k.allRequiredDependencies()).containsExactlyInAnyOrder(
                "org.apache.kafka:kafka-clients",
                "org.springframework.kafka:spring-kafka");
    }

    @Test
    void mappingRejectsBlankFeatureId() {
        assertThatThrownBy(() -> new Mapping(
                " ", "x", List.of(), List.of(), List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classDependencyRejectsBlankFields() {
        assertThatThrownBy(() -> new ClassDependency("", "dep"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassDependency("fqn", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
