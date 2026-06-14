package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Slf4j
public class PublishMessagesTask extends RetryTask<Void> {
    private static final Logger log = LoggerFactory.getLogger(PublishMessagesTask.class);
    private final IGoogleErrorConverter errorConverter;
    private final KafkaProducer<String, String> kafkaProducer;
    private final String topic;
    private final String message;

    @Override
    public Void call() throws Exception {
        try {
            log.debug("Publishing message to topic {}", topic);
            kafkaProducer.send(new ProducerRecord<>(topic, message));
            return null;
        } catch (SerializationException e) {
            log.error("Serialization failed for message to topic {}", topic, e);
            throw e;
        } catch (KafkaException e) {
            throw errorConverter.convert(e);
        }
    }
}