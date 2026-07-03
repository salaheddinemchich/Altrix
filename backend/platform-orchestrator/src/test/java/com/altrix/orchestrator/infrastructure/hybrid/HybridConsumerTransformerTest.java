package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic source-in consumer transform. Covers categories A, B, and
 * the C path (structure + deterministic publish rewrite to kafkaTemplate.send);
 * the mixed-class representation; each of the recognized bail triggers; and the
 * no-consumers regression. Outputs are asserted on exact structural fragments
 * (annotation, signature, bridged local, removed constructs) AND re-parsed to
 * prove they're valid Java — not just "contains a string".
 */
class HybridConsumerTransformerTest {

    private final HybridConsumerTransformer transformer = new HybridConsumerTransformer();

    /** Binds every subscription the fixtures use to its topic. */
    private static final String TOPIC_BOOTSTRAP = """
            package com.example.app;
            public class TopicBootstrap {
                void init() {
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.ORDERS_TOPIC), subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 30);
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.PAYMENTS_COMPLETED_TOPIC), subscription(PubSubConfig.PAYMENTS_COMPLETED_ORDERS_SUB), 30);
                    pubsub.getOrCreateSubscription(topic(PubSubConfig.ORDERS_TOPIC), subscription(PubSubConfig.ORDERS_PAYMENTS_SUB), 30);
                }
            }""";

    private Map<String, String> withBootstrap(String consumerPath, String consumerSource) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/com/example/app/TopicBootstrap.java", TOPIC_BOOTSTRAP);
        files.put(consumerPath, consumerSource);
        return files;
    }

    private Optional<MigratedFile> converted(HybridConsumerTransformer.Result r, String endsWith) {
        return r.convertedFiles().stream().filter(f -> f.originalPath().endsWith(endsWith)).findFirst();
    }

    private void assertValidJava(String content) {
        assertThat(new JavaParser().parse(content).isSuccessful())
                .as("emitted source must parse:\n" + content).isTrue();
    }

    // ── Category A ────────────────────────────────────────────────────────────

    private static final String A_CONSUMER = """
            package com.example.orders;
            import com.example.common.PubSubConfig;
            import com.example.common.PubSubService;
            import jakarta.ejb.Schedule;
            import jakarta.ejb.Singleton;
            import jakarta.ejb.Startup;
            import jakarta.inject.Inject;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            @Singleton
            @Startup
            public class OrderEventsPoller {
                private static final Logger log = LoggerFactory.getLogger(OrderEventsPoller.class);
                @Inject
                PubSubService pubsub;
                @Schedule(second = "*/3", minute = "*", hour = "*", persistent = false)
                public void pollOrders() {
                    pubsub.consume(PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 25, payload -> {
                        Order order = Order.fromMessage(payload);
                        log.info("Processing order {}", order.id());
                    });
                }
            }""";

    @Test
    void categoryA_convertsToKafkaListener_bodyVerbatim_noBridge() {
        var r = transformer.transform(withBootstrap("p/com/example/orders/OrderEventsPoller.java", A_CONSUMER));

        assertThat(r.bails()).isEmpty();
        String out = converted(r, "OrderEventsPoller.java").orElseThrow().content();
        assertValidJava(out);

        assertThat(out).contains("@Component");
        assertThat(out).contains(
                "@KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, groupId = PubSubConfig.ORDERS_PROCESSOR_SUB)");
        assertThat(out).contains("public void pollOrders(String payload)");
        assertThat(out).contains("Order order = Order.fromMessage(payload);");
        assertThat(out).contains("import org.springframework.kafka.annotation.KafkaListener;");
        // removed constructs
        assertThat(out).doesNotContain("@Schedule");
        assertThat(out).doesNotContain("@Inject");
        assertThat(out).doesNotContain("pubsub.consume");
        assertThat(out).doesNotContain("jakarta.ejb");
        // category-A → no CdiLookup, no KafkaTemplate
        assertThat(out).doesNotContain("CdiLookup");
        assertThat(out).doesNotContain("KafkaTemplate");
        // preserved non-@Inject field
        assertThat(out).contains("private static final Logger log");
    }

    // ── Category B (single CDI field → CdiLookup local) ───────────────────────

    private static final String B_CONSUMER = """
            package com.example.orders;
            import com.example.common.PubSubConfig;
            import com.example.common.PubSubService;
            import com.example.payments.Payment;
            import jakarta.ejb.Schedule;
            import jakarta.ejb.Singleton;
            import jakarta.ejb.Startup;
            import jakarta.inject.Inject;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            @Singleton
            @Startup
            public class OrderEventsPoller {
                private static final Logger log = LoggerFactory.getLogger(OrderEventsPoller.class);
                @Inject
                PubSubService pubsub;
                @Inject
                OrderStore orderStore;
                @Schedule(second = "*/3", minute = "*", hour = "*", persistent = false)
                public void pollPaymentsCompleted() {
                    pubsub.consume(PubSubConfig.subscription(PubSubConfig.PAYMENTS_COMPLETED_ORDERS_SUB), 25, payload -> {
                        Payment payment = Payment.fromMessage(payload);
                        orderStore.markPaid(payment.orderId());
                        log.info("Order {} marked PAID", payment.orderId());
                    });
                }
            }""";

    @Test
    void categoryB_bridgesInjectedField_viaCdiLookupLocal_exactlyOnce() {
        var r = transformer.transform(withBootstrap("p/com/example/orders/OrderEventsPoller.java", B_CONSUMER));

        assertThat(r.bails()).isEmpty();
        String out = converted(r, "OrderEventsPoller.java").orElseThrow().content();
        assertValidJava(out);

        assertThat(out).contains(
                "@KafkaListener(topics = PubSubConfig.PAYMENTS_COMPLETED_TOPIC, groupId = PubSubConfig.PAYMENTS_COMPLETED_ORDERS_SUB)");
        // the bridge local, inserted exactly once, before the body that uses it
        assertThat(out).contains("OrderStore orderStore = CdiLookup.get(OrderStore.class);");
        assertThat(countOccurrences(out, "CdiLookup.get(OrderStore.class)")).isEqualTo(1);
        assertThat(out.indexOf("CdiLookup.get(OrderStore.class)"))
                .isLessThan(out.indexOf("orderStore.markPaid"));
        // single-consumer project → base package = its own package
        assertThat(out).contains("import com.example.orders.CdiLookup;");
        // injected field declaration removed, but the bridged local replaces its use
        assertThat(out).doesNotContain("@Inject");
        assertThat(out).doesNotContain("KafkaTemplate"); // B has no publish
    }

    // ── Category C (structural convert + deterministic publish rewrite + @Autowired KafkaTemplate) ─

    private static final String C_CONSUMER = """
            package com.example.payments;
            import com.example.common.PubSubConfig;
            import com.example.common.PubSubService;
            import com.example.orders.Order;
            import jakarta.ejb.Schedule;
            import jakarta.ejb.Singleton;
            import jakarta.ejb.Startup;
            import jakarta.inject.Inject;
            import java.util.UUID;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            @Singleton
            @Startup
            public class PaymentEventsPoller {
                private static final Logger log = LoggerFactory.getLogger(PaymentEventsPoller.class);
                private static final double UNIT_PRICE = 9.99;
                @Inject
                PubSubService pubsub;
                @Inject
                PaymentStore paymentStore;
                @Schedule(second = "*/3", minute = "*", hour = "*", persistent = false)
                public void pollOrders() {
                    pubsub.consume(PubSubConfig.subscription(PubSubConfig.ORDERS_PAYMENTS_SUB), 25, payload -> {
                        Order order = Order.fromMessage(payload);
                        Payment payment = new Payment(UUID.randomUUID().toString(), order.id(), order.quantity() * UNIT_PRICE, "COMPLETED");
                        paymentStore.save(payment);
                        pubsub.publish(PubSubConfig.topic(PubSubConfig.PAYMENTS_COMPLETED_TOPIC), payment.toMessage());
                        log.info("Took payment {}", payment.id());
                    });
                }
            }""";

    @Test
    void categoryC_convertsStructure_bridgesField_rewritesPublishCall_addsKafkaTemplate() {
        var r = transformer.transform(withBootstrap("p/com/example/payments/PaymentEventsPoller.java", C_CONSUMER));

        assertThat(r.bails()).isEmpty(); // C is NOT a class-level bail — it's structurally converted
        String out = converted(r, "PaymentEventsPoller.java").orElseThrow().content();
        assertValidJava(out);

        // structure converted
        assertThat(out).contains("@Component");
        assertThat(out).contains(
                "@KafkaListener(topics = PubSubConfig.ORDERS_TOPIC, groupId = PubSubConfig.ORDERS_PAYMENTS_SUB)");
        assertThat(out).contains("public void pollOrders(String payload)");
        // CDI field bridged
        assertThat(out).contains("PaymentStore paymentStore = CdiLookup.get(PaymentStore.class);");
        // KafkaTemplate field added (needed for the send call below)
        assertThat(out).contains("@Autowired");
        assertThat(out).contains("KafkaTemplate<String, String> kafkaTemplate;");
        // publish call deterministically rewritten — no compile gap, no pubsub.publish left
        assertThat(out).contains("kafkaTemplate.send(PubSubConfig.PAYMENTS_COMPLETED_TOPIC, payment.toMessage())");
        assertThat(out).doesNotContain("pubsub.publish");
        // preserved non-@Inject static field used by the body
        assertThat(out).contains("UNIT_PRICE = 9.99");
    }

    // ── Mixed class: one A method + one C method in the same class ────────────

    private static final String MIXED_CONSUMER = """
            package com.example.orders;
            import com.example.common.PubSubConfig;
            import com.example.common.PubSubService;
            import jakarta.ejb.Schedule;
            import jakarta.ejb.Singleton;
            import jakarta.ejb.Startup;
            import jakarta.inject.Inject;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            @Singleton
            @Startup
            public class MixedPoller {
                private static final Logger log = LoggerFactory.getLogger(MixedPoller.class);
                @Inject
                PubSubService pubsub;
                @Schedule(second = "*/3", minute = "*", hour = "*", persistent = false)
                public void pollA() {
                    pubsub.consume(PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 25, payload -> log.info("A {}", payload));
                }
                @Schedule(second = "*/3", minute = "*", hour = "*", persistent = false)
                public void pollC() {
                    pubsub.consume(PubSubConfig.subscription(PubSubConfig.ORDERS_PAYMENTS_SUB), 25, payload -> {
                        pubsub.publish(PubSubConfig.topic(PubSubConfig.PAYMENTS_COMPLETED_TOPIC), payload);
                    });
                }
            }""";

    @Test
    void mixedClass_allMethodsStructurallyConvert_cMethodPublishRewritten() {
        var r = transformer.transform(withBootstrap("p/com/example/orders/MixedPoller.java", MIXED_CONSUMER));

        assertThat(r.bails()).isEmpty();
        String out = converted(r, "MixedPoller.java").orElseThrow().content();
        assertValidJava(out);

        // both methods are @KafkaListener (uniform @Component — no half-EJB/half-Spring)
        assertThat(out).contains("public void pollA(String payload)");
        assertThat(out).contains("public void pollC(String payload)");
        assertThat(countOccurrences(out, "@KafkaListener")).isEqualTo(2);
        // publish call deterministically rewritten — zero pubsub.publish left, KafkaTemplate added once
        assertThat(out).doesNotContain("pubsub.publish");
        assertThat(out).contains("kafkaTemplate.send(PubSubConfig.PAYMENTS_COMPLETED_TOPIC");
        assertThat(out).contains("KafkaTemplate<String, String> kafkaTemplate;");
        assertThat(out).doesNotContain("@Schedule");
    }

    // ── Bail triggers (Q3 list) ───────────────────────────────────────────────

    @Test
    void bail_noTopicBinding_leavesClassForLlm() {
        // ORDERS_REFUNDED_SUB has no binding in the bootstrap fixture.
        String consumer = A_CONSUMER.replace("ORDERS_PROCESSOR_SUB", "ORDERS_REFUNDED_SUB");
        var r = transformer.transform(withBootstrap("p/com/example/orders/OrderEventsPoller.java", consumer));

        assertThat(r.convertedFiles()).isEmpty();
        assertThat(r.bails()).hasSize(1);
        assertThat(r.bails().get(0).reason()).contains("topic binding");
    }

    @Test
    void bail_bodyHasMoreThanTheConsumeCall() {
        // Two statements in the @Schedule method body — not the single-consume shape.
        String consumer = """
                package com.example.orders;
                import com.example.common.PubSubConfig;
                import com.example.common.PubSubService;
                import jakarta.ejb.Schedule;
                import jakarta.inject.Inject;
                public class OrderEventsPoller {
                    @Inject
                    PubSubService pubsub;
                    @Schedule(second = "*/3")
                    public void pollOrders() {
                        System.out.println("extra statement");
                        pubsub.consume(PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 25, payload -> {});
                    }
                }""";
        var r = transformer.transform(withBootstrap("p/com/example/orders/OrderEventsPoller.java", consumer));
        assertThat(r.convertedFiles()).isEmpty();
        assertThat(r.bails()).hasSize(1);
    }

    @Test
    void bail_consumeArgIsNotSubscriptionHelper() {
        String consumer = A_CONSUMER.replace(
                "PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB)", "\"raw-sub-string\"");
        var r = transformer.transform(withBootstrap("p/com/example/orders/OrderEventsPoller.java", consumer));
        assertThat(r.convertedFiles()).isEmpty();
        assertThat(r.bails()).hasSize(1);
    }

    @Test
    void bail_consumeHasWrongArgCount() {
        String consumer = A_CONSUMER.replace(
                "PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), 25, payload ->",
                "PubSubConfig.subscription(PubSubConfig.ORDERS_PROCESSOR_SUB), payload ->");
        var r = transformer.transform(withBootstrap("p/com/example/orders/OrderEventsPoller.java", consumer));
        assertThat(r.convertedFiles()).isEmpty();
        assertThat(r.bails()).hasSize(1);
    }

    @Test
    void noConsumers_returnsEmpty_doesNotBreakNoListenersPath() {
        Map<String, String> files = Map.of(
                "p/Plain.java", "package p;\npublic class Plain { void hi() {} }",
                "p/Config.java", "package p;\npublic final class Config {}");
        var r = transformer.transform(files);
        assertThat(r.convertedFiles()).isEmpty();
        assertThat(r.bails()).isEmpty();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }
}
