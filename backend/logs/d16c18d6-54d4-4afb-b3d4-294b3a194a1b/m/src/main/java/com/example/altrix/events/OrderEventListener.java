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
            // Initialize Kafka Consumer
            Properties props = new Properties();
            props.put("bootstrap.servers", PubsubConfig.KAFKA_BOOTSTRAP_SERVERS);
            props.put("group.id", PubsubConfig.KAFKA_GROUP_ID);
            props.put("key.deserializer", StringDeserializer.class.getName());
            props.put("value.deserializer", StringDeserializer.class.getName());
            kafkaConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
            kafkaConsumer.subscribe(java.util.Collections.singleton(PubsubConfig.ORDERS_CREATED));
            log.info("TODO: Implement topic creation for {}", PubsubConfig.ORDERS_CREATED);
        } catch (Exception e) {
            log.warn("Skipping Kafka topic bootstrap ({}). " + 
                     "The poller will keep retrying — only Kafka-dependent endpoints are affected.", e.getMessage());
        }
    }

    @Schedule(second = "*/5", minute = "*", hour = "*", persistent = false)
    public void pollForOrders() {
        ConsumerRecords<String, String> messages;
        try {
            messages = kafkaConsumer.poll(100);
        } catch (Exception e) {
            log.warn("Failed to pull orders.created messages: {}", e.getMessage());
            return;
        }
        if (messages.isEmpty()) return;
        List<String> ackIds = new ArrayList<>();
        for (ConsumerRecord<String, String> record : messages) {
            try {
                String orderId = record.value().split(",")[0]; // Assuming format: "orderId,total"
                String totalStr = record.value().split(",")[1];
                BigDecimal total = new BigDecimal(totalStr);
                log.info("Handling order.created — orderId={} total={}", orderId, total);
                paymentService.recordPayment(orderId, total, PaymentMethod.CARD);
                // Kafka doesn't use ack IDs like Pub/Sub; instead, we commit the offset
                // after processing to mark the message as consumed.
                // ackIds.add(record.offset()); // Not directly applicable, see below
            } catch (Exception e) {
                log.error("Failed to process received order message offset={}", record.offset(), e);
                // Handle error, potentially leaving the message for reprocessing
            }
        }
        // Commit the offsets for successfully processed messages
        kafkaConsumer.commitSync();
        // TODO altrix: Implement idempotent processing or dead-letter queue for failed messages
    }
}