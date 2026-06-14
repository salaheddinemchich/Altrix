package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class AcknowledgeMessagesTask extends RetryTask<Void> {
    private static final Logger log = LoggerFactory.getLogger(AcknowledgeMessagesTask.class);
    private final Object errorConverter;
    private final Object kafkaConsumer;
    private final List<TopicPartition> partitions;
    private final List<Long> offsets;

    public AcknowledgeMessagesTask(Object errorConverter, Object kafkaConsumer, List<TopicPartition> partitions, List<Long> offsets) {
        this.errorConverter = errorConverter;
        this.kafkaConsumer = kafkaConsumer;
        this.partitions = partitions;
        this.offsets = offsets;
    }

    public Void call() throws Exception {
        try {
            log.debug("Committing {} offsets for partitions {}", offsets.size(), partitions);
            Object offsetsToCommit = new Object();
            for (int i = 0; i < partitions.size(); i++) {
                // Assuming OffsetAndMetadata requires TopicPartition and long offset
                // Since actual types are unknown, using Object for demonstration
                ((Object) offsetsToCommit).put(partitions.get(i), new Object(offsets.get(i)));
            }
            ((Object) kafkaConsumer).commitAsync(offsetsToCommit, new OffsetCommitCallback() {
                public void onComplete(Object offsets, Exception exception) {
                    if (exception != null) {
                        log.error("Error committing offsets", exception);
                        throw new KafkaException("Commit failed", exception);
                    }
                    log.debug("Offsets committed successfully");
                }
            });
            return null;
        } catch (Exception e) {
            // Assuming errorConverter has a method to convert Exception
            // If not, this line would need adjustment based on actual errorConverter type
            throw (Exception) ((Object) errorConverter).convert(e);
        }
    }
}