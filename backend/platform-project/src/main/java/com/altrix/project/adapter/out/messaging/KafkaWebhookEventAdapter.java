package com.altrix.project.adapter.out.messaging;

import com.altrix.project.domain.model.webhook.NewCommitDetected;
import com.altrix.project.domain.port.out.WebhookEventPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code migration.commit.detected} events when the webhook
 * receiver accepts a push (#90).
 *
 * <p>Message format follows the pipe-delimited convention used by the other
 * Altrix Kafka topics so downstream consumers can split without a JSON parse:
 * <pre>
 *   key   = deliveryId
 *   value = repoUrl|branch|commitSha
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaWebhookEventAdapter implements WebhookEventPublisherPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.commit-detected:migration.commit.detected}")
    private String topic;

    @Override
    public void publish(NewCommitDetected event) {
        String value = event.repoUrl() + "|" + event.branch() + "|" + event.commitSha();

        log.info("Publishing migration.commit.detected: repo='{}' branch='{}' sha={} deliveryId={}",
                event.repoUrl(), event.branch(), event.commitSha(), event.deliveryId());

        kafkaTemplate.send(topic, event.deliveryId(), value)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish migration.commit.detected for deliveryId '{}': {}",
                                event.deliveryId(), ex.getMessage());
                    }
                });
    }
}
