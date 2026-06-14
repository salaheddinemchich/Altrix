package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PublishMessagesTask extends RetryTask<Void> {
    private static final Logger log = LoggerFactory.getLogger(PublishMessagesTask.class);
    private final IGoogleErrorConverter errorConverter;
    private final KafkaProducer<String, String> kafkaProducer;
    private final String topicName;
    private final List<ProducerRecord<String, String>> records;

    public PublishMessagesTask(IGoogleErrorConverter errorConverter, KafkaProducer<String, String> kafkaProducer, String topicName, List<ProducerRecord<String, String>> records) {
        this.errorConverter = errorConverter;
        this.kafkaProducer = kafkaProducer;
        this.topicName = topicName;
        this.records = records;
    }

    @Override
    public Void call() throws Exception {
        try {
            log.debug("Publishing {} records to topic {}", records.size(), topicName);
            for (ProducerRecord<String, String> record : records) {
                kafkaProducer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        log.error("Error publishing record to {}", topicName, exception);
                        throw new KafkaException("Send failed", exception);
                    }
                    log.debug("Record sent to {} successfully", metadata.topic());
                });
            }
            return null;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}