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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

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

    private KafkaConsumer<String, String> consumer;

    @PostConstruct
    void onStartup() {
        log.info("Ensuring topic exist for orders.created");
        // Kafka topics are auto-created by default when producing/consuming if not exists
        // No direct equivalent for getOrCreateTopic/Sub in Kafka, relying on auto-creation

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, PubsubConfig.ORDERS_CREATED_PAYMENT_SUB);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // Equivalent to pulling from the beginning

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singleton(PubsubConfig.ORDERS_CREATED));
    }

    @Schedule(second = "*/5", minute = "*", hour = "*", persistent = false)
    public void pollForOrders() {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
        if (records.isEmpty()) return;

        List<TopicPartition> partitionsToCommit = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                // Assuming orderId and total are now part of the message value (e.g., JSON)
                // For demonstration, a simple split is used; in real scenarios, use a JSON parser
                String[] parts = record.value().split(",");
                if (parts.length < 2) {
                    log.error("Invalid message format: {}", record.value());
                    continue;
                }
                String orderId = parts[0];
                String totalStr = parts[1];
                BigDecimal total = new BigDecimal(totalStr);
                log.info("Handling order.created — orderId={} total={}", orderId, total);
                paymentService.recordPayment(orderId, total, PaymentMethod.CARD);

                partitionsToCommit.add(new TopicPartition(record.topic(), record.partition(), record.offset()));
            } catch (Exception e) {
                log.error("Failed to process received order message: {}", record, e);
            }
        }
        if (!partitionsToCommit.isEmpty()) {
            consumer.commitSync(partitionsToCommit);
        }
    }
}