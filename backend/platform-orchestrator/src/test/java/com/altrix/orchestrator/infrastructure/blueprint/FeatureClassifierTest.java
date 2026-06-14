package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.BlueprintFeature;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.Mapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureClassifierTest {

    /** KB with the mappings the classifier joins to for kafkaTarget + notes. */
    private KafkaMigrationKnowledgeBase kb() {
        return new KafkaMigrationKnowledgeBase(List.of(
                new Mapping("pubsub.publish-single", "producer.send(record)",
                        List.of(), List.of(), List.of("org.apache.kafka:kafka-clients"), List.of(),
                        "Pub/Sub publish becomes KafkaProducer.send. No request types."),
                new Mapping("pubsub.pull", "consumer.poll(Duration)",
                        List.of(), List.of(), List.of(), List.of(),
                        "Pull becomes KafkaConsumer.poll."),
                new Mapping("pubsub.ack", "consumer.commitSync()",
                        List.of(), List.of(), List.of(), List.of(),
                        "Ack becomes commitSync."),
                new Mapping("pubsub.test-iam", "// TODO altrix: no Kafka equivalent",
                        List.of(), List.of(), List.of(), List.of(),
                        "IAM has no clean Kafka equivalent.")),
                List.of(), List.of());
    }

    private FeatureClassifier classifier() {
        return new FeatureClassifier(kb());
    }

    @Test
    void detectsPublishAndEnrichesWithKafkaTarget() {
        var features = classifier().classifyOne("""
                package p;
                public class Svc {
                    void publishOrderCreated() {
                        pubsubService.publish(topic, message);
                    }
                }
                """);
        assertThat(features).extracting(BlueprintFeature::id).contains("pubsub.publish-single");
        var publish = features.stream().filter(f -> f.id().equals("pubsub.publish-single"))
                .findFirst().orElseThrow();
        assertThat(publish.kafkaTarget()).isEqualTo("producer.send(record)");
        assertThat(publish.evidence().line()).isGreaterThan(0);
        assertThat(publish.description()).contains("KafkaProducer.send");
    }

    @Test
    void detectsLegacyRestChains() {
        var features = classifier().classifyOne("""
                package p;
                public class Task {
                    void go() {
                        pubsub.projects().subscriptions().pull(name, request).execute();
                    }
                }
                """);
        assertThat(features).extracting(BlueprintFeature::id).contains("pubsub.pull");
    }

    @Test
    void detectsAckAndIam() {
        var ack = classifier().classifyOne(
                "class A { void m() { pubsubService.acknowledge(sub, ids); } }");
        assertThat(ack).extracting(BlueprintFeature::id).contains("pubsub.ack");

        var iam = classifier().classifyOne(
                "class B { void m() { service.testIAMPermissionsOnTopic(t, perms); } }");
        assertThat(iam).extracting(BlueprintFeature::id).contains("pubsub.test-iam");
    }

    @Test
    void oneFeaturePerIdEvenWithMultipleOccurrences() {
        var features = classifier().classifyOne("""
                class A {
                    void a() { svc.publish(t1, m1); }
                    void b() { svc.publish(t2, m2); }
                }
                """);
        long publishCount = features.stream().filter(f -> f.id().equals("pubsub.publish-single")).count();
        assertThat(publishCount).isEqualTo(1);
    }

    @Test
    void cleanFileHasNoFeatures() {
        var features = classifier().classifyOne("""
                package p;
                public class PlainPojo {
                    private String name;
                    public String getName() { return name; }
                }
                """);
        assertThat(features).isEmpty();
    }

    @Test
    void classifyMapSkipsFilesWithoutFeatures() {
        var result = classifier().classify(Map.of(
                "p/Svc.java", "class Svc { void m() { x.publish(a, b); } }",
                "p/Plain.java", "class Plain { int x; }"));
        assertThat(result).containsKey("p/Svc.java");
        assertThat(result).doesNotContainKey("p/Plain.java");
    }

    @Test
    void featureWithoutKbMappingStillEmittedWithNullTarget() {
        // A classifier whose KB lacks the mapping — feature still detected,
        // kafkaTarget is null, description falls back.
        var bareKb = new KafkaMigrationKnowledgeBase(List.of(), List.of(), List.of());
        var c = new FeatureClassifier(bareKb);
        var features = c.classifyOne("class A { void m() { x.publish(a, b); } }");
        assertThat(features).extracting(BlueprintFeature::id).contains("pubsub.publish-single");
        assertThat(features.get(0).kafkaTarget()).isNull();
        assertThat(features.get(0).description()).isEqualTo("Detected pubsub.publish-single");
    }

    @Test
    void emptyInputIsSafe() {
        assertThat(classifier().classify(null)).isEmpty();
        assertThat(classifier().classify(Map.of())).isEmpty();
        assertThat(classifier().classifyOne("")).isEmpty();
    }
}
