package com.example.altrix.events;

import com.example.altrix.common.PubsubConfig;
import com.example.altrix.payment.PaymentMethod;
import com.example.altrix.payment.PaymentService;
import com.example.altrix.pubsub.PubsubService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

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
    PaymentService paymentService;

    @Inject
    PubsubService pubsubService;

    private KafkaConsumer<String, String> consumer;

    @PostConstruct
    void onStartup() {
        log.info("Ensuring topic exist for orders.created");
        try {
            pubsubService.createTopic(PubsubConfig.ORDERS_CREATED);
        } catch (Exception e) {
            log.warn("Skipping Kafka topic bootstrap ({}). " +
                    "The poller will keep retrying — only Kafka-dependent endpoints are affected.",
                    e.getMessage());
        }
        // Initialize Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, PubsubConfig.ORDERS_CREATED_PAYMENT_SUB);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singleton(PubsubConfig.ORDERS_CREATED));
    }

    @Schedule(second = "*/5", minute = "*", hour = "*", persistent = false)
    public void pollForOrders() {
        try {
            consumer.poll(Duration.ofSeconds(5)).forEach(record -> {
                try {
                    // Assuming orderId and total are now part of the message value (e.g., JSON)
                    String messageValue = record.value();
                    // Simple example: parse orderId and total from messageValue (actual parsing may vary)
                    String[] parts = messageValue.split(",");
                    String orderId = parts[0];
                    String totalStr = parts[1];
                    BigDecimal total = new BigDecimal(totalStr);
                    log.info("Handling order.created — orderId={} total={}", orderId, total);
                    paymentService.recordPayment(orderId, total, PaymentMethod.CARD);
                } catch (Exception e) {
                    log.error("Failed to process received order message", e);
                }
            });
            consumer.commitSync(); // Commit after processing all records in the poll
        } catch (Exception e) {
            log.warn("Failed to pull orders.created messages: {}", e.getMessage());
        }
    }
}