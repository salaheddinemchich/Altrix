package com.altrix.orchestrator.infrastructure.hybrid;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Glue detection for the hybrid target's wrapper deletion.  Anchored on the
 * two shapes the real source project exhibits ({@code PubSubService} =
 * {@code @Inject} raw client + publish/consume seam; {@code PubsubClientProducer}
 * = CDI {@code @Produces} of the raw client) and the non-shapes that must be
 * left for the LLM (business classes that merely touch a client type, classes
 * with the seam but no raw client, already-migrated Kafka wrappers).
 */
class PubSubWrapperRemoverTest {

    private final PubSubWrapperRemover remover = new PubSubWrapperRemover();

    // ── the two glue shapes ───────────────────────────────────────────────────

    private static final String WRAPPER_SERVICE = """
            package com.example.common;
            import com.google.api.services.pubsub.Pubsub;
            import com.google.api.services.pubsub.model.PubsubMessage;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;
            @ApplicationScoped
            public class PubSubService {
                @Inject
                Pubsub pubsub;
                public void publish(String topic, String payload) { }
                public void consume(String sub, int max, java.util.function.Consumer<String> h) { }
            }""";

    private static final String CLIENT_PRODUCER = """
            package com.example.common;
            import com.google.api.services.pubsub.Pubsub;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.inject.Produces;
            import jakarta.inject.Singleton;
            @ApplicationScoped
            public class PubsubClientProducer {
                @Produces
                @Singleton
                public Pubsub createPubsubClient() { return null; }
            }""";

    @Test
    void detectsWrapperService_injectedClientPlusSeamMethods() {
        Set<String> out = remover.detect(Map.of("src/PubSubService.java", WRAPPER_SERVICE));
        assertThat(out).containsExactly("src/PubSubService.java");
    }

    @Test
    void detectsClientProducer_cdiProducesRawClient() {
        Set<String> out = remover.detect(Map.of("src/PubsubClientProducer.java", CLIENT_PRODUCER));
        assertThat(out).containsExactly("src/PubsubClientProducer.java");
    }

    @Test
    void detectsBoth_inOnePass_realProjectShape() {
        Set<String> out = remover.detect(Map.of(
                "src/PubSubService.java", WRAPPER_SERVICE,
                "src/PubsubClientProducer.java", CLIENT_PRODUCER,
                "src/Poller.java", "package p;\npublic class Poller {}"));
        assertThat(out).containsExactlyInAnyOrder(
                "src/PubSubService.java", "src/PubsubClientProducer.java");
    }

    @Test
    void wildcardClientImport_stillDetected_viaKnownClientTypeNames() {
        String wildcard = """
                package com.example.common;
                import com.google.api.services.pubsub.*;
                import jakarta.inject.Inject;
                public class PubSubService {
                    @Inject
                    Pubsub pubsub;
                    public void consume(String sub) { }
                }""";
        assertThat(remover.detect(Map.of("src/PubSubService.java", wildcard)))
                .containsExactly("src/PubSubService.java");
    }

    // ── non-shapes that must be left alone ────────────────────────────────────

    @Test
    void businessClassTouchingClientType_withoutSeam_leftForLlm() {
        String business = """
                package com.example.orders;
                import com.google.api.services.pubsub.model.PubsubMessage;
                public class OrderMapper {
                    public String decode(PubsubMessage m) { return m.toString(); }
                }""";
        assertThat(remover.detect(Map.of("src/OrderMapper.java", business))).isEmpty();
    }

    @Test
    void injectedClientWithoutSeamMethods_leftForLlm() {
        String noSeam = """
                package com.example.common;
                import com.google.api.services.pubsub.Pubsub;
                import jakarta.inject.Inject;
                public class HealthProbe {
                    @Inject
                    Pubsub pubsub;
                    public boolean ping() { return pubsub != null; }
                }""";
        assertThat(remover.detect(Map.of("src/HealthProbe.java", noSeam))).isEmpty();
    }

    @Test
    void migratedKafkaWrapper_noClientImport_notDetected() {
        // The already-migrated form (retry overlay) — no GCP import → pre-filter skips.
        String kafkaWrapper = """
                package com.example.common;
                import org.apache.kafka.clients.producer.KafkaProducer;
                public class PubSubService {
                    private KafkaProducer<String, String> producer;
                    public void publish(String topic, String payload) { }
                    public void consume(String topic) { }
                }""";
        assertThat(remover.detect(Map.of("src/PubSubService.java", kafkaWrapper))).isEmpty();
    }

    @Test
    void interfaceWithClientImport_notDetected() {
        String iface = """
                package com.example.common;
                import com.google.api.services.pubsub.Pubsub;
                public interface MessagingSeam {
                    void publish(String topic, String payload);
                }""";
        assertThat(remover.detect(Map.of("src/MessagingSeam.java", iface))).isEmpty();
    }

    @Test
    void unparseableFile_skippedWithoutException() {
        String broken = "package com.google.api.services.pubsub garbage {{{";
        assertThat(remover.detect(Map.of("src/Broken.java", broken))).isEmpty();
    }

    @Test
    void nonJavaAndNullEntries_ignored() {
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("pom.xml", "<project>com.google.api.services.pubsub</project>");
        files.put("src/Null.java", null);
        assertThat(remover.detect(files)).isEmpty();
        assertThat(remover.detect(null)).isEmpty();
        assertThat(remover.detect(Map.of())).isEmpty();
    }
}
