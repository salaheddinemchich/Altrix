package com.example.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes orders to a Kafka topic via {@link KafkaTemplate} — the
 * idiomatic Spring Kafka publish path.
 */
@Service
public class OrderPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Order order) {
        log.info("Publishing order {} to topic {}", order.id(), PubSubConfig.ORDERS_TOPIC);
        kafkaTemplate.send(PubSubConfig.ORDERS_TOPIC, order.id(), order.toMessage());
    }
}