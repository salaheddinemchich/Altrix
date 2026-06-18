package com.example.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * A minimal external-client stand-in: it only consumes, never publishes,
 * representing a notification service (email/SMS) reacting to payment
 * events from its own consumer groups.
 */
@Service
public class NotificationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NotificationSubscriber.class);

    @KafkaListener(topics = "${notifications.payments-completed-topic:payments-completed}", groupId = "${spring.kafka.consumer.group-id}")
    public void handlePaymentCompleted(String payload) {
        log.info("Notifying customer: payment completed — {}", payload);
    }

    @KafkaListener(topics = "${notifications.payments-refunded-topic:payments-refunded}", groupId = "${spring.kafka.consumer.group-id}")
    public void handlePaymentRefunded(String payload) {
        log.info("Notifying customer: payment refunded — {}", payload);
    }
}