package com.example.altrix.events;

import com.example.altrix.common.PubsubConfig;
import com.example.altrix.payment.PaymentMethod;
import com.example.altrix.payment.PaymentService;
import com.example.altrix.pubsub.PubsubService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import java.time.Duration;
/**
 * Background poller — pulls messages from {@code orders.created} every 5 seconds
 * and triggers a payment for each new order. This is the read-side counterpart
 * to {@link com.example.altrix.order.OrderService#createOrder} on the write side.
 */
@Slf4j
@Singleton
@Startup
public class OrderEventListener {

    @Inject
    PubsubService pubsubService;

    @Inject
    PaymentService paymentService;

    private org.apache.kafka.clients.consumer.KafkaConsumer<String, String> kafkaConsumer;

    @PostConstruct
    void onStartup() {
        log.info("Ensuring topic + subscription exist for orders.created");
        // Wrapped so a Kafka outage doesn't crash the whole application at boot —
        // the @Schedule poller below already handles per-poll failures
        // gracefully, so the right thing on startup is to log and carry on.
        try {
            // Kafka doesn't require explicit topic creation like Pub/Sub
            // If needed, implement in PubsubService or find an equivalent
            // pubsubService.getOrCreateTopic(PubsubConfig.ORDERS_CREATED);
        } catch (Exception e) {
            log.warn("Skipping Kafka topic bootstrap ({}). " +
                    "The poller will keep retrying — only Kafka-dependent endpoints are affected.", e.getMessage());
        }
        
        // Initialize Kafka Consumer
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092"); // Replace with actual Kafka brokers
        props.put("group.id", "order-listener");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        kafkaConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
        kafkaConsumer.subscribe(List.of(PubsubConfig.ORDERS_CREATED));
    }

    @Schedule(second = "*/5", minute = "*", hour = "*", persistent = false)
    public void pollForOrders() {
        ConsumerRecords<String, String> messages;
        try {
            messages = kafkaConsumer.poll(java.time.Duration.ofMillis(100));
        } catch (Exception e) {
            log.warn("Failed to pull orders.created messages: {}", e.getMessage());
            return;
        }
        if (messages.isEmpty()) {
            kafkaConsumer.commitSync(); // Commit if no messages to process
            return;
        }
        List<TopicPartition> ackPartitions = new ArrayList<>();
        for (ConsumerRecord<String, String> record : messages) {
            try {
                // Assuming attributes are now part of the Kafka message value (e.g., JSON)
                String messageValue = record.value();
                // Deserialize messageValue to extract orderId and total (implementation omitted for brevity)
                String orderId = "extractFromMessageValue"; // TODO altrix: Implement actual deserialization
                String totalStr = "extractFromMessageValue"; // TODO altrix: Implement actual deserialization
                BigDecimal total = new BigDecimal(totalStr);
                log.info("Handling order.created — orderId={} total={}", orderId, total);
                paymentService.recordPayment(orderId, total, PaymentMethod.CARD);
                ackPartitions.add(new TopicPartition(record.topic(), record.partition(), record.offset()));
            } catch (Exception e) {
                log.error("Failed to process received order message offset={}", record.offset(), e);
            }
        }
        // Manual commit for acknowledged messages
        kafkaConsumer.commitSync(ackPartitions);
    }
}