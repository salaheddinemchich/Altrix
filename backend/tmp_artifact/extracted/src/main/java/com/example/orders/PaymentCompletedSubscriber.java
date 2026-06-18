package com.example.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Reacts to payment completion by marking the order PAID — orders' own
 * consumer group on the payments.completed topic, alongside payments
 * domain's {@code NotificationSubscriber}.
 */
@Service
public class PaymentCompletedSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedSubscriber.class);

    private final OrderStore orderStore;

    public PaymentCompletedSubscriber(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    @KafkaListener(topics = "payments.completed", groupId = "orders-payment-completed")
    public void handle(@Payload String payload) {
        PaymentCompletedEvent event = PaymentCompletedEvent.fromMessage(payload);
        orderStore.markPaid(event.orderId());
        log.info("Order {} marked PAID (payment {})", event.orderId(), event.paymentId());
    }
}