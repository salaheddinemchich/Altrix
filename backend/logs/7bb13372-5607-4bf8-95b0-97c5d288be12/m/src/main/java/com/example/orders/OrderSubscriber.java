package com.example.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class OrderSubscriber {
    private static final Logger log = LoggerFactory.getLogger(OrderSubscriber.class);

    @KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, groupId = "orders.created.processor")
    public void handle(@Payload String payload) {
        Order order = Order.fromMessage(payload);
        log.info("Processing order {} ({} x{})", order.id(), order.product(), order.quantity());
    }
}
