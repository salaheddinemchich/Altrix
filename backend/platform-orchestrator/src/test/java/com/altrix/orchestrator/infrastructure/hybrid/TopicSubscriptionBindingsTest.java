package com.altrix.orchestrator.infrastructure.hybrid;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopicSubscriptionBindingsTest {

    private static final String TOPIC_BOOTSTRAP = """
            package com.example.app;
            import static com.example.common.PubSubConfig.subscription;
            import static com.example.common.PubSubConfig.topic;
            public class TopicBootstrap {
                void init() {
                    pubsub.getOrCreateTopic(topic(PubSubConfig.ORDERS_TOPIC));
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.ORDERS_TOPIC), subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 30);
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.ORDERS_TOPIC), subscription(PubSubConfig.ORDERS_PAYMENTS_SUB), 30);
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.PAYMENTS_COMPLETED_TOPIC), subscription(PubSubConfig.PAYMENTS_COMPLETED_ORDERS_SUB), 30);
                }
            }""";

    @Test
    void buildsSubToTopicMapFromBootstrapShape() {
        var bindings = TopicSubscriptionBindings.from(Map.of("src/main/java/com/example/app/TopicBootstrap.java", TOPIC_BOOTSTRAP));

        assertThat(bindings.size()).isEqualTo(3);
        assertThat(bindings.topicFor("ORDERS_PROCESSOR_SUB")).contains("ORDERS_TOPIC");
        assertThat(bindings.topicFor("ORDERS_PAYMENTS_SUB")).contains("ORDERS_TOPIC");
        assertThat(bindings.topicFor("PAYMENTS_COMPLETED_ORDERS_SUB")).contains("PAYMENTS_COMPLETED_TOPIC");
    }

    @Test
    void missingBinding_returnsEmpty_neverGuesses() {
        var bindings = TopicSubscriptionBindings.from(Map.of("p/TopicBootstrap.java", TOPIC_BOOTSTRAP));
        assertThat(bindings.topicFor("PAYMENTS_REFUNDED_NOTIFICATIONS_SUB")).isEmpty();
    }

    @Test
    void noBootstrapFile_yieldsEmptyBindings() {
        var bindings = TopicSubscriptionBindings.from(Map.of("p/Foo.java", "package p; class Foo {}"));
        assertThat(bindings.isEmpty()).isTrue();
        assertThat(bindings.topicFor("ANYTHING")).isEmpty();
    }

    @Test
    void constantName_unwrapsHelperAndQualifier() {
        // topic(PubSubConfig.ORDERS_TOPIC) / PubSubConfig.ORDERS_TOPIC / ORDERS_TOPIC all → ORDERS_TOPIC
        var wrapped = com.github.javaparser.StaticJavaParser.parseExpression("topic(PubSubConfig.ORDERS_TOPIC)");
        var qualified = com.github.javaparser.StaticJavaParser.parseExpression("PubSubConfig.ORDERS_TOPIC");
        var bare = com.github.javaparser.StaticJavaParser.parseExpression("ORDERS_TOPIC");
        assertThat(TopicSubscriptionBindings.constantName(wrapped)).isEqualTo("ORDERS_TOPIC");
        assertThat(TopicSubscriptionBindings.constantName(qualified)).isEqualTo("ORDERS_TOPIC");
        assertThat(TopicSubscriptionBindings.constantName(bare)).isEqualTo("ORDERS_TOPIC");
    }
}
