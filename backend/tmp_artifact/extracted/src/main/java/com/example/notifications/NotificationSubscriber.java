package com.example.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * A minimal external-client stand-in: it only consumes, never publishes,
 * representing a notification service (email/SMS) reacting to payment
 * events from its own consumer groups.
 */
@Service
public class NotificationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NotificationSubscriber.class);

    @KafkaListener(topics = "${payments.completed.topic}", groupId = "notification-service-completed")
    public void handlePaymentCompleted(@Payload String payload) {
        log.info("Notifying customer: payment completed — {}", payload);
    }

    @KafkaListener(topics = "${payments.refunded.topic}", groupId = "notification-service-refunded")
    public void handlePaymentRefunded(@Payload String payload) {
        log.info("Notifying customer: payment refunded — {}", payload);
    }
}