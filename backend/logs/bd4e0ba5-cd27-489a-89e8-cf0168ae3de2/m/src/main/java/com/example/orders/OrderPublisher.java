package com.example.orders;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Properties;

/**
 * Publishes orders to a Kafka topic — the idiomatic Kafka publish path.
 */
@Service
public class OrderPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderPublisher.class);
    private final KafkaProducer<String, String> kafkaProducer;
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${pubsub.orders.topic}")
    private String ordersTopic;

    public OrderPublisher() {
        this.kafkaProducer = new KafkaProducer<>(getProducerProps());
    }

    private Properties getProducerProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        return props;
    }

    public void publish(Order order) {
        log.info("Publishing order {} to topic {}", order.id(), ordersTopic);
        ProducerRecord<String, String> record = new ProducerRecord<>(ordersTopic, order.toMessage());
        kafkaProducer.send(record); // Blocking send for simplicity; consider async in production
    }
}