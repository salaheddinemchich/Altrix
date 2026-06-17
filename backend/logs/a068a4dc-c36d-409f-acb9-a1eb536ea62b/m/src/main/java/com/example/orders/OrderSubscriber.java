package com.example.orders;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @PostConstruct
    public void start() {
        log.info("Subscribed to topic {}", PubSubConfig.ORDERS_TOPIC);
    }

    @KafkaListener(topics = PubSubConfig.ORDERS_TOPIC)
    public void handle(@Payload String payload, 
                       @Header(KafkaHeaders.RECEIVED_PARTITION) int partition, 
                       @Header(KafkaHeaders.OFFSET) long offset, 
                       Acknowledgment acknowledgment) {
        Order order = Order.fromMessage(payload);
        log.info("Processing order {} ({} x{})", order.id(), order.product(), order.quantity());
        acknowledgment.acknowledge();
    }
}