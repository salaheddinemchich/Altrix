package com.example.orders;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Subscribes to the orders topic and processes each message, acking on success.
 */
@Service
public class OrderSubscriber {

    private static final Logger log = LoggerFactory.getLogger(OrderSubscriber.class);
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderSubscriber(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void handle(@Payload String payload, 
                       @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                       @Header(KafkaHeaders.OFFSET) long offset,
                       Acknowledgment acknowledgment) {
        Order order = Order.fromMessage(payload);
        log.info("Processing order {} ({} x{})", order.id(), order.product(), order.quantity());
        acknowledgment.acknowledge();
    }
}