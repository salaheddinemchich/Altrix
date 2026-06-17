package com.example.orders;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;

import java.util.Collections;
import java.util.Map;

import java.util.HashMap;
/**
 * Subscribes to the orders topic and processes each message.
 * The idiomatic Spring Kafka consumer.
 */
@Service
public class OrderSubscriber {

    private static final Logger log = LoggerFactory.getLogger(OrderSubscriber.class);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaMessageListenerContainer container;

    @PostConstruct
    public void start() {
        container.start();
        log.info("Subscribed to topic {}", PubSubConfig.ORDERS_TOPIC);
    }

    @KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, containerFactory = "kafkaListenerContainerFactory")
    public void handle(@Payload String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                       @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                       @Header(KafkaHeaders.OFFSET) long offset) {
        Order order = Order.fromMessage(payload);
        log.info("Processing order {} ({} x{}) from topic {} partition {} offset {}",
                order.id(), order.product(), order.quantity(), topic, partition, offset);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }

    @Bean
    public DefaultKafkaConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = getConsumerProps();
        return new DefaultKafkaConsumerFactory<>(props);
    }

    private Map<String, Object> getConsumerProps() {
        Map<String, Object> props = new java.util.HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "orders-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }
}