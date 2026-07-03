package com.altrix.orchestrator.infrastructure.hybrid;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link TopicBootstrapAnchor}: it recognizes a {@code @Singleton @Startup}
 * Pub/Sub bootstrap by shape (never by class name), harvests its topic constants
 * in source order, reports the file for deletion, and bails (null) on anything
 * that isn't an unambiguous topic bootstrap.
 */
class TopicBootstrapAnchorTest {

    private final TopicBootstrapAnchor anchor = new TopicBootstrapAnchor();

    private static final String BOOTSTRAP = """
            package com.example.app;

            import com.example.common.PubSubConfig;
            import com.example.common.PubSubService;
            import jakarta.annotation.PostConstruct;
            import jakarta.ejb.Singleton;
            import jakarta.ejb.Startup;
            import jakarta.inject.Inject;

            import static com.example.common.PubSubConfig.subscription;
            import static com.example.common.PubSubConfig.topic;

            @Singleton
            @Startup
            public class TopicBootstrap {

                private static final int ACK_DEADLINE = 30;

                @Inject
                PubSubService pubsub;

                @PostConstruct
                void init() {
                    pubsub.getOrCreateTopic(topic(PubSubConfig.ORDERS_TOPIC));
                    pubsub.getOrCreateTopic(topic(PubSubConfig.PAYMENTS_COMPLETED_TOPIC));
                    pubsub.getOrCreateTopic(topic(PubSubConfig.PAYMENTS_REFUNDED_TOPIC));
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.ORDERS_TOPIC),
                            subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), ACK_DEADLINE);
                }
            }
            """;

    private Map<String, String> files(String... pathThenContent) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pathThenContent.length; i += 2) {
            m.put(pathThenContent[i], pathThenContent[i + 1]);
        }
        return m;
    }

    @Test
    void harvestsTopicConstantsInSourceOrder_andReportsPathForDeletion() {
        TopicBootstrapResult result = anchor.analyze(
                files("src/main/java/com/example/app/TopicBootstrap.java", BOOTSTRAP));

        assertThat(result).isNotNull();
        assertThat(result.sourceFilePath()).isEqualTo("src/main/java/com/example/app/TopicBootstrap.java");
        // Only the three getOrCreateTopic(...) constants — never the subscription constant.
        assertThat(result.topicConstantNames())
                .containsExactly("ORDERS_TOPIC", "PAYMENTS_COMPLETED_TOPIC", "PAYMENTS_REFUNDED_TOPIC");
    }

    @Test
    void resultSetIsImmutable() {
        TopicBootstrapResult result = anchor.analyze(
                files("src/main/java/com/example/app/TopicBootstrap.java", BOOTSTRAP));

        assertThat(result).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.topicConstantNames().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsNull_whenClassIsNotSingletonStartup() {
        // Same getOrCreateTopic calls, but a plain class — not a recognized bootstrap.
        String plain = BOOTSTRAP.replace("@Singleton\n@Startup\n", "");
        assertThat(anchor.analyze(files("src/main/java/com/example/app/Plain.java", plain))).isNull();
    }

    @Test
    void returnsNull_whenNoGetOrCreateTopicCall() {
        String noTopics = """
                package com.example.app;
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;
                @Singleton
                @Startup
                public class TopicBootstrap {
                    void init() {}
                }
                """;
        assertThat(anchor.analyze(files("src/main/java/com/example/app/TopicBootstrap.java", noTopics))).isNull();
    }

    @Test
    void returnsNull_onEmptyInput() {
        assertThat(anchor.analyze(Map.of())).isNull();
        assertThat(anchor.analyze(null)).isNull();
    }
}
