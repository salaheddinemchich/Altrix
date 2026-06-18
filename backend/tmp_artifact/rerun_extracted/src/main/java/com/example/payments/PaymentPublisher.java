package com.example.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes payment lifecycle events via {@link KafkaTemplate} — the same
 * idiomatic publish path {@code com.example.orders.OrderPublisher} uses.
 */
@Service
public class PaymentPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public PaymentPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCompleted(Payment payment) {
        log.info("Publishing payment {} to topic {}", payment.id(), PubSubConfig.PAYMENTS_COMPLETED_TOPIC);
        kafkaTemplate.send(PubSubConfig.PAYMENTS_COMPLETED_TOPIC, payment.id(), payment.toMessage());
    }

    public void publishRefunded(Payment payment) {
        log.info("Publishing refund {} to topic {}", payment.id(), PubSubConfig.PAYMENTS_REFUNDED_TOPIC);
        kafkaTemplate.send(PubSubConfig.PAYMENTS_REFUNDED_TOPIC, payment.id(), payment.toMessage());
    }
}