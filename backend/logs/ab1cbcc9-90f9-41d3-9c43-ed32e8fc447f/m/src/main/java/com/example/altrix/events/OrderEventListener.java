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
import org.apache.kafka.clients.consumer.KafkaConsumer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import java.util.Collections;
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
        log.info("Ensuring Kafka consumer is configured for orders.created");
        Properties props = new Properties();
        props.put("bootstrap.servers", PubsubConfig.KAFKA_BOOTSTRAP_SERVERS);
        props.put("group.id", PubsubConfig.ORDERS_CREATED_PAYMENT_SUB);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(java.util.Collections.singleton(PubsubConfig.ORDERS_CREATED));
    }

    @Schedule(second = "*/5", minute = "*", hour = "*", persistent = false)
    public void pollForOrders() {
        ConsumerRecords<String, String> records = consumer.poll(100);
        if (records.isEmpty()) return;

        List<String> ackIds = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                String orderId = record.headers().lastHeaderValue("orderId").orElseThrow();
                String totalStr = record.headers().lastHeaderValue("total").orElse("0");
                BigDecimal total = new BigDecimal(totalStr);
                log.info("Handling order.created — orderId={} total={}", orderId, total);
                paymentService.recordPayment(orderId, total, PaymentMethod.CARD);
                ackIds.add(record.offset() + ""); // Note: Kafka doesn't have direct "ackId"
            } catch (Exception e) {
                log.error("Failed to process received order message offset={}", record.offset(), e);
            }
        }
        consumer.commitSync(); // Acknowledge all consumed records
    }
}