package com.example.payments;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
/**
 * Payments' own consumer group on the orders.created topic — takes payment
 * for every order placed and publishes the result.
 */
@Service
public class PaymentRequestSubscriber {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentRequestSubscriber.class);
    private static final double UNIT_PRICE = 9.99;

    private final PaymentStore paymentStore;
    private final PaymentPublisher paymentPublisher;

    public PaymentRequestSubscriber(PaymentStore paymentStore, PaymentPublisher paymentPublisher) {
        this.paymentStore = paymentStore;
        this.paymentPublisher = paymentPublisher;
    }

    @KafkaListener(topics = "orders.created", groupId = "payments")
    public void handle(String payload) {
        OrderCreatedEvent order = OrderCreatedEvent.fromMessage(payload);
        Payment payment = new Payment(UUID.randomUUID().toString(), order.id(),
                order.quantity() * UNIT_PRICE, "COMPLETED");
        paymentStore.save(payment);
        paymentPublisher.publishCompleted(payment);
        log.info("Took payment {} for order {}", payment.id(), order.id());
    }
}