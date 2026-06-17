package com.altrix.orchestrator.infrastructure.semantic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringKafkaOverEngineeringDetectorTest {

    private final SpringKafkaOverEngineeringDetector detector = new SpringKafkaOverEngineeringDetector();

    @Test
    void flags_KafkaListener_coexisting_with_manual_container() {
        // The bd4e0ba5 OrderSubscriber shape: @KafkaListener + @Autowired container.
        String src = """
                package p;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.kafka.listener.KafkaMessageListenerContainer;
                import org.springframework.beans.factory.annotation.Autowired;
                import jakarta.annotation.PostConstruct;
                public class OrderSubscriber {
                    @Autowired
                    private KafkaMessageListenerContainer container;
                    @PostConstruct void start() { container.start(); }
                    @KafkaListener(topics = "orders") public void handle(String m) { }
                }""";
        var found = detector.detect(Map.of("p/OrderSubscriber.java", src));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).symbol()).isEqualTo("KafkaMessageListenerContainer");
        assertThat(found.get(0).message()).contains("UnsatisfiedDependencyException");
    }

    @Test
    void flags_redundant_consumerFactory_bean_in_listener_class() {
        String src = """
                package p;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.kafka.core.ConsumerFactory;
                import org.springframework.context.annotation.Bean;
                public class OrderSubscriber {
                    @Bean public ConsumerFactory<String, String> consumerFactory() { return null; }
                    @KafkaListener(topics = "orders") public void handle(String m) { }
                }""";
        var found = detector.detect(Map.of("p/OrderSubscriber.java", src));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).symbol()).isEqualTo("ConsumerFactory");
        assertThat(found.get(0).message()).contains("auto-configures");
    }

    @Test
    void does_not_flag_manual_container_when_no_KafkaListener_present() {
        // A manual container as the ONLY consumer mechanism is a legitimate design.
        String src = """
                package p;
                import org.springframework.kafka.listener.KafkaMessageListenerContainer;
                import org.springframework.beans.factory.annotation.Autowired;
                public class ManualConsumer {
                    @Autowired private KafkaMessageListenerContainer container;
                    void start() { container.start(); }
                }""";
        assertThat(detector.detect(Map.of("p/ManualConsumer.java", src))).isEmpty();
    }

    @Test
    void does_not_flag_clean_listener() {
        String src = """
                package p;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderSubscriber {
                    @KafkaListener(topics = "orders") public void handle(String m) { }
                }""";
        assertThat(detector.detect(Map.of("p/OrderSubscriber.java", src))).isEmpty();
    }

    @Test
    void ignoresNonJavaAndEmpty() {
        assertThat(detector.detect(Map.of("README.md", "@KafkaListener"))).isEmpty();
        assertThat(detector.detect(Map.of())).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}
