package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PublishMessagesTask extends RetryTask<RecordMetadata> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaProducer<String, String> kafkaProducer;
    private final String topicName;
    private final String[] messages;

    public RecordMetadata call() throws Exception {
        try {
            log.debug("Publishing {} message(s) to {}", messages.length, topicName);
            for (String message : messages) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topicName, message);
                return kafkaProducer.send(record).get();
            }
            // If no messages are sent (empty array), return null to indicate no metadata
            return null;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}