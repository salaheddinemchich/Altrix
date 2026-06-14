package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourcePattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import org.apache.kafka.clients.admin.AdminClient;
public class PubsubTopicTestIAMPermissionsTask extends RetryTask<List<String>> {
    private static final Logger log = LoggerFactory.getLogger(PubsubTopicTestIAMPermissionsTask.class);
    private final IGoogleErrorConverter errorConverter;
    private final String topicName;
    private final List<String> permissions;

    public PubsubTopicTestIAMPermissionsTask(IGoogleErrorConverter errorConverter, String topicName, List<String> permissions) {
        this.errorConverter = errorConverter;
        this.topicName = topicName;
        this.permissions = permissions;
    }

    @Override
    public List<String> call() throws Exception {
        try {
            // Kafka equivalent: Check ACLs via AdminClient (not shown, requires additional setup)
            log.debug("Testing permissions {} on topic {} (Kafka ACL check not implemented)", permissions, topicName);
            // TODO altrix: Implement Kafka ACL check using AdminClient
            return Collections.unmodifiableList(permissions);
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}