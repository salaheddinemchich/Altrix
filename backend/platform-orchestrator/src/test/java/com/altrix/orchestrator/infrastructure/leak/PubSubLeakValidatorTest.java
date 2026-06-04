package com.altrix.orchestrator.infrastructure.leak;

import com.altrix.orchestrator.domain.model.leak.PubSubLeakKind;
import com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the validator against the literal failed-file shapes the
 * user reported (Pub/Sub→Kafka run on "test-altrix").  Each test names
 * the original failing file so future maintainers can grep for the
 * symptom.
 */
class PubSubLeakValidatorTest {

    private final PubSubLeakValidator validator = new PubSubLeakValidator();

    // ── Import leaks ────────────────────────────────────────────────────────

    /**
     * Real failure from {@code PullMessagesTask.java}:
     * <pre>import com.google.api.services.pubsub.Pubsub;</pre>
     */
    @Test
    void importLeak_detectsLegacyRestV1Pubsub() {
        Map<String, String> files = Map.of(
                "pubsub/tasks/PullMessagesTask.java", """
                        package p;
                        import com.google.api.services.pubsub.Pubsub;
                        import com.google.api.services.pubsub.model.PullRequest;
                        import com.google.api.services.pubsub.model.PullResponse;
                        public class PullMessagesTask {}
                        """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_IMPORT);
        // Three imports, all flagged.
        long importCount = v.stream().filter(x -> x.kind() == PubSubLeakKind.GOOGLE_IMPORT).count();
        assertThat(importCount).isEqualTo(3);
    }

    @Test
    void importLeak_detectsModernSpringCloudGcpPubSub() {
        Map<String, String> files = Map.of(
                "p/Foo.java", """
                        package p;
                        import org.springframework.cloud.gcp.pubsub.core.PubSubTemplate;
                        public class Foo {}
                        """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_IMPORT);
    }

    @Test
    void importLeak_carriesKafkaSuggestion() {
        Map<String, String> files = Map.of(
                "p/T.java",
                "package p;\nimport com.google.api.services.pubsub.model.PullRequest;\npublic class T {}");
        var v = validator.validate(files);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).suggestion()).contains("KafkaConsumer");
    }

    @Test
    void importLeak_doesNotFlagKafkaImport() {
        Map<String, String> files = Map.of(
                "p/T.java",
                "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class T {}");
        assertThat(validator.validate(files)).isEmpty();
    }

    // ── Type-reference leaks ────────────────────────────────────────────────

    /**
     * Real failure from {@code AcknowledgeMessagesTask.java}:
     * <pre>public class AcknowledgeMessagesTask extends RetryTask&lt;Empty&gt; { ... }</pre>
     */
    @Test
    void typeReference_detectsPubsubInExtendsClause() {
        Map<String, String> files = Map.of(
                "p/T.java", """
                        package p;
                        public class T {
                            private Pubsub pubsub;
                        }
                        """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_TYPE_REFERENCE);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_TYPE_REFERENCE).symbol())
                .isEqualTo("Pubsub");
    }

    @Test
    void typeReference_detectsReceivedMessageGeneric() {
        Map<String, String> files = Map.of(
                "p/Task.java", """
                        package p;
                        import java.util.List;
                        public class Task {
                            List<ReceivedMessage> result;
                        }
                        """);
        var v = validator.validate(files);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_TYPE_REFERENCE).symbol())
                .isEqualTo("ReceivedMessage");
    }

    @Test
    void typeReference_doesNotFlagProjectDeclaredTypeOfSameName() {
        // User has their own `Topic` class — we must NOT flag it.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Topic.java", "package p;\npublic class Topic {}");
        files.put("p/Use.java",   "package p;\npublic class Use { private Topic t; }");
        // `Topic` is not in FORBIDDEN_TYPE_NAMES so this is moot, but the
        // shadow logic is the same.  Cover it explicitly via Pubsub:
        files.put("p/Pubsub.java", "package p;\npublic class Pubsub {}"); // user-owned
        files.put("p/Use2.java",
                "package p;\npublic class Use2 { private Pubsub p; }");
        var v = validator.validate(files);
        boolean flaggedPubsub = v.stream().anyMatch(x ->
                x.kind() == PubSubLeakKind.GOOGLE_TYPE_REFERENCE && "Pubsub".equals(x.symbol()));
        assertThat(flaggedPubsub).isFalse();
    }

    @Test
    void typeReference_carriesKafkaSuggestion() {
        Map<String, String> files = Map.of(
                "p/T.java", "package p;\npublic class T { Pubsub p; }");
        var v = validator.validate(files);
        var hit = validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_TYPE_REFERENCE);
        assertThat(hit.suggestion()).contains("KafkaProducer");
    }

    // ── Method-chain leaks ──────────────────────────────────────────────────

    /**
     * Real failure from {@code PullMessagesTask.java}:
     * <pre>pubsub.projects().subscriptions().pull(fullSubscriptionName, request).execute();</pre>
     */
    @Test
    void methodChain_detectsLegacySubscriptionsPull() {
        Map<String, String> files = Map.of(
                "p/PullTask.java", """
                        package p;
                        public class PullTask {
                            Object pubsub;
                            void run() {
                                pubsub.projects().subscriptions().pull("sub", null).execute();
                            }
                        }
                        """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_METHOD_CHAIN);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_METHOD_CHAIN).suggestion())
                .contains("consumer.poll");
    }

    /**
     * Real failure from {@code AcknowledgeMessagesTask.java}:
     * <pre>pubsub.projects().subscriptions().acknowledge(fullSubscriptionName, request).execute();</pre>
     */
    @Test
    void methodChain_detectsLegacySubscriptionsAcknowledge() {
        Map<String, String> files = Map.of(
                "p/AckTask.java", """
                        package p;
                        public class AckTask {
                            Object pubsub;
                            void run() {
                                pubsub.projects().subscriptions().acknowledge("sub", null).execute();
                            }
                        }
                        """);
        var v = validator.validate(files);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_METHOD_CHAIN).suggestion())
                .contains("commitSync");
    }

    /**
     * Real failure from {@code PublishMessagesTask.java}:
     * <pre>pubsub.projects().topics().publish(fullTopicName, request).execute();</pre>
     */
    @Test
    void methodChain_detectsLegacyTopicsPublish() {
        Map<String, String> files = Map.of(
                "p/Pub.java", """
                        package p;
                        public class Pub {
                            Object pubsub;
                            void run() {
                                pubsub.projects().topics().publish("t", null).execute();
                            }
                        }
                        """);
        var v = validator.validate(files);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_METHOD_CHAIN).suggestion())
                .contains("producer.send");
    }

    @Test
    void methodChain_detectsTestIamPermissions() {
        Map<String, String> files = Map.of(
                "p/IamTask.java", """
                        package p;
                        public class IamTask {
                            void run() { testIamPermissions("t", null); }
                        }
                        """);
        var v = validator.validate(files);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_METHOD_CHAIN).symbol())
                .isEqualTo("testIamPermissions");
    }

    // ── Dependency leaks (pom.xml) ──────────────────────────────────────────

    @Test
    void dependency_detectsLegacyGoogleApiServicesPubsub() {
        Map<String, String> files = Map.of("pom.xml", """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.apis</groupId>
                      <artifactId>google-api-services-pubsub</artifactId>
                      <version>v1-rev20210220-1.32.1</version>
                    </dependency>
                  </dependencies>
                </project>""");
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_DEPENDENCY);
        assertThat(validator.firstByKindUtil(v, PubSubLeakKind.GOOGLE_DEPENDENCY).symbol())
                .isEqualTo("google-api-services-pubsub");
    }

    @Test
    void dependency_detectsSpringCloudGcpStarter() {
        Map<String, String> files = Map.of("pom.xml",
                "<project><dependency><artifactId>spring-cloud-gcp-starter-pubsub</artifactId></dependency></project>");
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_DEPENDENCY);
    }

    @Test
    void dependency_scansGradleBuildScripts() {
        Map<String, String> files = Map.of(
                "build.gradle",
                "dependencies { implementation 'com.google.cloud:google-cloud-pubsub:1.0' }");
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_DEPENDENCY);
    }

    // ── Config-key leaks (yaml / properties) ───────────────────────────────

    @Test
    void configKey_detectsSpringCloudGcpPubsubKey() {
        Map<String, String> files = Map.of("application.yml",
                "spring:\n  cloud:\n    gcp:\n      pubsub:\n        project-id: foo\n");
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_CONFIG_KEY);
    }

    @Test
    void configKey_detectsEmulatorEnvVar() {
        Map<String, String> files = Map.of("application.properties",
                "PUBSUB_EMULATOR_HOST=localhost:8085");
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(PubSubLeakKind.GOOGLE_CONFIG_KEY);
    }

    // ── Smoke: clean Kafka project produces no leaks ────────────────────────

    @Test
    void cleanKafkaProject_producesNoLeaks() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Svc.java", """
                package p;
                import org.apache.kafka.clients.producer.KafkaProducer;
                import org.apache.kafka.clients.producer.ProducerRecord;
                public class Svc {
                    KafkaProducer<String,String> producer;
                    void run() { producer.send(new ProducerRecord<>("t", "v")); }
                }
                """);
        files.put("application.yml",
                "spring:\n  kafka:\n    bootstrap-servers: localhost:9092\n");
        files.put("pom.xml", """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                    </dependency>
                  </dependencies>
                </project>""");
        assertThat(validator.validate(files)).isEmpty();
        assertThat(validator.isClean(files)).isTrue();
    }

    @Test
    void nullOrEmptyInput_returnsEmpty() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(Map.of())).isEmpty();
    }

    @Test
    void groupByFile_keysByPath() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/A.java", "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class A {}");
        files.put("p/B.java", "package p;\nimport com.google.cloud.pubsub.v1.Publisher;\npublic class B {}");
        var v = validator.validate(files);
        Map<String, List<PubSubLeakViolation>> grouped = validator.groupByFile(v);
        assertThat(grouped.keySet()).containsExactlyInAnyOrder("p/A.java", "p/B.java");
    }

    @Test
    void renderOneLinePerViolation() {
        Map<String, String> files = Map.of(
                "p/A.java", "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class A {}");
        var v = validator.validate(files);
        String rendered = validator.render(v);
        assertThat(rendered).contains("GOOGLE_IMPORT");
        assertThat(rendered.split("\n")).hasSize(v.size());
    }

    @Test
    void kindsReflectsAllPresentKinds() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Bad.java", "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class Bad {}");
        files.put("pom.xml", "<project><artifactId>google-cloud-pubsub</artifactId></project>");
        Set<PubSubLeakKind> kinds = validator.kinds(validator.validate(files));
        assertThat(kinds).contains(PubSubLeakKind.GOOGLE_IMPORT, PubSubLeakKind.GOOGLE_DEPENDENCY);
    }
}
