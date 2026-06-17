package com.example.orders;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Publishes orders to a Kafka topic via {@link KafkaProducer} — the
 * idiomatic Kafka publish path.
 */
@Service
public class OrderPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderPublisher.class);
    private final KafkaProducer<String, String> kafkaProducer;

    public OrderPublisher() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        this.kafkaProducer = new KafkaProducer<>(props);
    }

    public void publish(Order order) {
        log.info("Publishing order {} to topic {}", order.id(), PubSubConfig.ORDERS_TOPIC);
        kafkaProducer.send(new ProducerRecord<>(PubSubConfig.ORDERS_TOPIC, order.id(), order.toMessage()));
    }
}