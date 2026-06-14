package com.example.altrix.pubsub.tasks;

import com.example.altrix.pubsub.IGoogleErrorConverter;
import com.example.altrix.pubsub.PubsubTopic;
import com.example.altrix.pubsub.RetryTask;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.security.authorizer.Acl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import java.util.Collections;
import java.util.List;

import org.apache.kafka.clients.admin.AdminClient;
@RequiredArgsConstructor
@Slf4j
public class PubsubTopicTestIAMPermissionsTask extends RetryTask<List<String>> {
    private final IGoogleErrorConverter errorConverter;
    private final PubsubTopic topic;
    private final List<String> permissions;

    @Override
    public List<String> call() throws Exception {
        try {
            // TODO altrix: Implement Kafka ACL check equivalent
            // For demonstration, assume read operation permission check
            // In real scenarios, use Kafka's AdminClient to manage ACLs
            log.debug("Simulating IAM permission test for {} on topic {}", permissions, topic.getFullTopicName());
            // Example ACL check (simplified, not a real Kafka ACL operation)
            if (permissions.contains(AclOperation.READ.name())) {
                return Collections.unmodifiableList(permissions);
            } else {
                throw new SecurityException("Insufficient permissions");
            }
        } catch (Exception e) {
            throw errorConverter.convert(e);
        }
    }
}