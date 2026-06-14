package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
@RequiredArgsConstructor
public class AcknowledgeMessagesTask extends RetryTask<Void> {
    private static final Logger log = LoggerFactory.getLogger(AcknowledgeMessagesTask.class);
    private final IGoogleErrorConverter errorConverter;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final List<TopicPartition> partitions;
    private final long[] offsets;

    @Override
    public Void call() throws Exception {
        try {
            log.debug("Committing {} offsets for partitions {}", offsets.length, partitions);
            kafkaConsumer.commitAsync(
                    getOffsetsMap(partitions, offsets),
                    new OffsetCommitCallback() {
                        @Override
                        public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
                            if (exception != null) {
                                log.error("Commit failed", exception);
                                throw new KafkaException("Offset commit failed", exception);
                            }
                            log.debug("Offsets committed successfully");
                        }
                    });
            return null;
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> getOffsetsMap(List<TopicPartition> partitions, long[] offsets) {
        Map<TopicPartition, OffsetAndMetadata> offsetsMap = new HashMap<>();
        for (int i = 0; i < partitions.size(); i++) {
            offsetsMap.put(partitions.get(i), new OffsetAndMetadata(offsets[i]));
        }
        return offsetsMap;
    }
}