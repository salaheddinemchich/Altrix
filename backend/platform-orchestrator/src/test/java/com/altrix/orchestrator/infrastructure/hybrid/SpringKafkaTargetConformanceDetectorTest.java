package com.altrix.orchestrator.infrastructure.hybrid;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the raw-kafka-clients drift detector (root cause 2 of job
 * 2c91a1fc). The detector itself is pattern-based and NOT target-gated — its
 * gating ({@code SPRING_KAFKA_HYBRID} only) lives in {@code SemanticValidatorAgent}
 * and is covered by {@code JakartaSpringKafkaHybridPipelineTest}. These tests pin
 * the pattern detection and the scaffolding-exclusion guard.
 */
class SpringKafkaTargetConformanceDetectorTest {

    private final SpringKafkaTargetConformanceDetector detector = new SpringKafkaTargetConformanceDetector();

    private List<SpringKafkaTargetConformanceDetector.Detection> detect(String content) {
        return detector.detect(Map.of("p/PubSubService.java", content));
    }

    @Test
    void flagsDirectKafkaProducerConstruction() {
        String src = """
                package p;
                import org.apache.kafka.clients.producer.KafkaProducer;
                public class PubSubService {
                    private KafkaProducer<String, String> p;
                    void init() { p = new KafkaProducer<>(new java.util.Properties()); }
                }""";
        assertThat(detect(src)).anyMatch(d -> d.symbol().equals("new KafkaProducer"));
    }

    @Test
    void flagsDirectKafkaConsumerConstruction() {
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.KafkaConsumer;
                public class PubSubService {
                    void consume() {
                        KafkaConsumer<String, String> c = new KafkaConsumer<>(new java.util.Properties());
                    }
                }""";
        assertThat(detect(src)).anyMatch(d -> d.symbol().equals("new KafkaConsumer"));
    }

    @Test
    void flagsAdminClientCreate() {
        String src = """
                package p;
                import org.apache.kafka.clients.admin.AdminClient;
                public class PubSubService {
                    void admin() { AdminClient a = AdminClient.create(new java.util.Properties()); }
                }""";
        assertThat(detect(src)).anyMatch(d -> d.symbol().equals("AdminClient.create"));
    }

    @Test
    void flagsPropertiesMethodCalls() {
        String src = """
                package p;
                public class PubSubService {
                    void init() {
                        var a = pubSubConfig.producerProperties();
                        var b = pubSubConfig.consumerProperties();
                        var c = pubSubConfig.adminProperties();
                    }
                }""";
        List<SpringKafkaTargetConformanceDetector.Detection> out = detect(src);
        assertThat(out).anyMatch(d -> d.symbol().equals("producerProperties()"));
        assertThat(out).anyMatch(d -> d.symbol().equals("consumerProperties()"));
        assertThat(out).anyMatch(d -> d.symbol().equals("adminProperties()"));
    }

    @Test
    void detectionMessageIsTargetSpecificAndActionable() {
        String src = """
                package p;
                import org.apache.kafka.clients.producer.KafkaProducer;
                public class PubSubService {
                    KafkaProducer<String, String> p = new KafkaProducer<>(new java.util.Properties());
                }""";
        var first = detect(src).get(0);
        assertThat(first.message())
                .contains("Spring Kafka hybrid")
                .contains("PubSubService")
                .contains("KafkaTemplate")
                .contains("@KafkaListener");
    }

    @Test
    void doesNotFlagGeneratedSpringKafkaConfig() {
        // The generated SpringKafkaConfig legitimately builds
        // DefaultKafkaProducerFactory/DefaultKafkaConsumerFactory — correct for
        // the hybrid bridge, must never be flagged as drift.
        String springKafkaConfig = """
                package com.example;
                import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
                import org.springframework.kafka.core.DefaultKafkaProducerFactory;
                public class SpringKafkaConfig {
                    Object producerFactory() { return new DefaultKafkaProducerFactory<>(new java.util.HashMap<>()); }
                    Object consumerFactory() { return new DefaultKafkaConsumerFactory<>(new java.util.HashMap<>()); }
                }""";
        assertThat(detector.detect(Map.of("com/example/SpringKafkaConfig.java", springKafkaConfig)))
                .isEmpty();
    }

    @Test
    void cleanHybridFileProducesNoDetections() {
        String src = """
                package p;
                import org.springframework.kafka.core.KafkaTemplate;
                import jakarta.inject.Inject;
                public class OrderPublisher {
                    @Inject KafkaTemplate<String, String> kafkaTemplate;
                    void publish(String t, String m) { kafkaTemplate.send(t, m); }
                }""";
        assertThat(detect(src)).isEmpty();
    }

    @Test
    void emptyInputIsSafe() {
        assertThat(detector.detect(Map.of())).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}
