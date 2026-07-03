package com.altrix.orchestrator.infrastructure.config;

import com.altrix.common.domain.enums.JakartaMessagingTarget;
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
        return new KafkaMigrationKnowledgeBase(mappings, deps, globalForbidden, List.of());
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
        var k = new KafkaMigrationKnowledgeBase(null, null, null, null);
        assertThat(k.mappings()).isEmpty();
        assertThat(k.classDependencies()).isEmpty();
        assertThat(k.globalForbiddenImports()).isEmpty();
        assertThat(k.forbiddenDependencyArtifacts()).isEmpty();
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
                        "org.apache.kafka:kafka-clients", false)),
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
                        "org.apache.kafka:kafka-clients", false)),
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
        assertThatThrownBy(() -> new ClassDependency("", "dep", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassDependency("fqn", " ", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dependencyForClass_1ArgOverload_matchesRegardlessOfHybridOnlyFlag() {
        var k = kb(List.of(),
                List.of(new ClassDependency("org.springframework.context.*",
                        "org.springframework:spring-context", true)),
                List.of());
        assertThat(k.dependencyForClass("org.springframework.context.ApplicationContext"))
                .contains("org.springframework:spring-context");
    }

    @Test
    void dependencyForClass_2ArgOverload_skipsHybridOnlyMapping_whenTargetIsNotHybrid() {
        var k = kb(List.of(),
                List.of(new ClassDependency("org.springframework.context.*",
                        "org.springframework:spring-context", true)),
                List.of());
        assertThat(k.dependencyForClass("org.springframework.context.ApplicationContext",
                JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS)).isEmpty();
        assertThat(k.dependencyForClass("org.springframework.context.ApplicationContext", null)).isEmpty();
    }

    @Test
    void dependencyForClass_2ArgOverload_matchesHybridOnlyMapping_whenTargetIsHybrid() {
        var k = kb(List.of(),
                List.of(new ClassDependency("org.springframework.context.*",
                        "org.springframework:spring-context", true)),
                List.of());
        assertThat(k.dependencyForClass("org.springframework.context.ApplicationContext",
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID)).contains("org.springframework:spring-context");
    }

    @Test
    void dependencyForClass_2ArgOverload_nonHybridOnlyMapping_matchesRegardlessOfTarget() {
        var k = kb(List.of(),
                List.of(new ClassDependency(
                        "org.apache.kafka.clients.consumer.KafkaConsumer", "org.apache.kafka:kafka-clients", false)),
                List.of());
        assertThat(k.dependencyForClass("org.apache.kafka.clients.consumer.KafkaConsumer",
                JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS)).contains("org.apache.kafka:kafka-clients");
    }
}
