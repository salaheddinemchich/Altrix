package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.PubsubTopic;
import com.example.altrix.pubsub.RetryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.Collections;
import java.util.List;

import org.apache.kafka.clients.admin.AdminClient;
@Slf4j
@RequiredArgsConstructor
public class PubsubTopicTestIAMPermissionsTask extends RetryTask<List<String>> {
    private final IGoogleErrorConverter errorConverter;
    private final PubsubTopic topic;
    private final List<String> permissions;

    public List<String> call() throws Exception {
        try {
            // TODO altrix: No direct Kafka equivalent for IAM permission testing.
            // Use Kafka AdminClient to manage ACLs or integrate with an external IAM system.
            log.debug("Testing IAM permissions {} on topic (Kafka ACL setup required) {}", permissions, topic.getFullTopicName());
            return Collections.unmodifiableList(permissions);
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}