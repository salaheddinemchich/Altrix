package com.example.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Payments' own consumer group on the orders.created topic — takes payment
 * for every order placed and publishes the result. Pulls via
 * {@link org.springframework.kafka.annotation.KafkaListener}, the same idiomatic path
 * {@code com.example.orders.OrderSubscriber} uses.
 */
@Service
public class PaymentRequestSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestSubscriber.class);
    private static final double UNIT_PRICE = 9.99;

    private final PaymentStore paymentStore;
    private final PaymentPublisher paymentPublisher;

    public PaymentRequestSubscriber(PaymentStore paymentStore,
                                     PaymentPublisher paymentPublisher) {
        this.paymentStore = paymentStore;
        this.paymentPublisher = paymentPublisher;
    }

    @KafkaListener(topics = PaymentRequestSubscriber.ORDERS_CREATED_TOPIC, groupId = "payments-consumer-group")
    public void handle(@Payload String payload) {
        OrderCreatedEvent order = OrderCreatedEvent.fromMessage(payload);
        Payment payment = new Payment(UUID.randomUUID().toString(), order.id(),
                order.quantity() * UNIT_PRICE, "COMPLETED");
        paymentStore.save(payment);
        paymentPublisher.publishCompleted(payment);
        log.info("Took payment {} for order {}", payment.id(), order.id());
    }

    public static final String ORDERS_CREATED_TOPIC = "orders.created";
}