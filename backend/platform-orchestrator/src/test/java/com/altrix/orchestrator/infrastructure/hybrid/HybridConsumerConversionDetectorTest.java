package com.altrix.orchestrator.infrastructure.hybrid;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HybridConsumerConversionDetectorTest {

    private final HybridConsumerConversionDetector detector = new HybridConsumerConversionDetector();

    private List<HybridConsumerConversionDetector.Detection> detect(String content) {
        return detector.detect(Map.of("p/X.java", content));
    }

    @Test
    void flagsCategoryCPublishGap_withTargetedSendInstruction() {
        String src = """
                package com.example.payments;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Component;
                @Component
                public class PaymentEventsPoller {
                    @KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, groupId = PubSubConfig.ORDERS_PAYMENTS_SUB)
                    public void pollOrders(String payload) {
                        pubsub.publish(PubSubConfig.topic(PubSubConfig.PAYMENTS_COMPLETED_TOPIC), payment.toMessage());
                    }
                }""";
        var found = detect(src);
        assertThat(found).hasSize(1);
        var d = found.get(0);
        assertThat(d.symbol()).contains("pollOrders");
        assertThat(d.message())
                .contains("already been converted to a @KafkaListener")
                .contains("kafkaTemplate.send(")
                .contains("PAYMENTS_COMPLETED_TOPIC")
                .contains("payment.toMessage()")
                .contains("Do not modify any other part");
    }

    @Test
    void flagsUnconvertedPoller_stillCallingConsume() {
        String src = """
                package com.example.orders;
                import jakarta.ejb.Schedule;
                public class OrderEventsPoller {
                    @Schedule(second = "*/3")
                    public void pollOrders() {
                        pubsub.consume(PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 25, payload -> log.info("x"));
                    }
                }""";
        var found = detect(src);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).symbol()).contains("pollOrders");
        assertThat(found.get(0).message())
                .contains("not converted to a Spring @KafkaListener")
                .contains("CdiLookup.get");
    }

    @Test
    void cleanConvertedConsumer_producesNoFindings() {
        String src = """
                package com.example.orders;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Component;
                @Component
                public class OrderEventsPoller {
                    @KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, groupId = PubSubConfig.ORDERS_PROCESSOR_SUB)
                    public void pollOrders(String payload) {
                        Order order = Order.fromMessage(payload);
                        log.info("Processing {}", order.id());
                    }
                }""";
        assertThat(detect(src)).isEmpty();
    }

    @Test
    void emptyInputIsSafe() {
        assertThat(detector.detect(Map.of())).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}
