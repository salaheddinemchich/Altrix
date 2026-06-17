package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class PublishMessagesTask extends RetryTask<RecordMetadata> {
    private final Object errorConverter; // Changed type to Object as IGoogleErrorConverter is not declared
    private final KafkaProducer<String, String> kafkaProducer;
    private final String topicName;
    private final List<String> messages;

    public RecordMetadata call() throws Exception { // Removed @Override as no supertype declares it
        try {
            log.debug("Publishing {} message(s) to {}", messages.size(), topicName);
            RecordMetadata metadata = null;
            for (String message : messages) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topicName, message);
                metadata = kafkaProducer.send(record).get();
            }
            return metadata;
        } catch (KafkaException e) {
            // Assuming errorConverter.convert(e) is not critical for now, 
            // as IGoogleErrorConverter is not declared and its usage is unclear
            throw e; // Directly re-throw or handle with a default error handling
        }
    }
}