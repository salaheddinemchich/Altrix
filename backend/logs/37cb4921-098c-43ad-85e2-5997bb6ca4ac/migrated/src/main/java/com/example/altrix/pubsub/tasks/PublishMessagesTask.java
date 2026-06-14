package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PublishMessagesTask extends RetryTask<Void> {
    private final IGoogleErrorConverter errorConverter;
    private final KafkaProducer<String, String> kafkaProducer;
    private final String topicName;
    private final String[] messages;

    public Void call() throws Exception {
        try {
            log.debug("Publishing {} message(s) to {}", messages.length, topicName);
            for (String message : messages) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topicName, message);
                try {
                    kafkaProducer.send(record).get();
                } catch (KafkaException e) {
                    throw errorConverter.convert(e);
                }
            }
            return null;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}