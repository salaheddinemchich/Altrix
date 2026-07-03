package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PubSubConfigAnchor}: it rewrites a {@code PubSubConfig}-shaped
 * constants class to constants-only — preserving the topic/subscription constant
 * names and values byte-for-byte while stripping the {@code @Configuration} /
 * {@code @Bean} pollution, the GCP {@code topic()}/{@code subscription()} helpers,
 * the non-literal {@code PROJECT_ID} field, all imports and all comments — and the
 * result re-parses. It detects by shape (no class-name hardcoding). A pure
 * constants class still fires (idempotent rewrite) so it is always kept out of
 * the LLM set and cannot receive spurious {@code @Configuration}/{@code @Bean}
 * additions on a retry job.
 */
class PubSubConfigAnchorTest {

    private final PubSubConfigAnchor anchor = new PubSubConfigAnchor();

    private static final String POLLUTED_CONFIG = """
            package com.example.common;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.kafka.core.KafkaTemplate;

            /**
             * Pub/Sub config — builds projects/{id}/topics/{name} REST paths the
             * com.google.api.services.pubsub client expects.
             */
            @Configuration
            public class PubSubConfig {

                public static final String PROJECT_ID = System.getProperty("gcp.project.id", "altrix-local");

                // Topics
                public static final String ORDERS_TOPIC = "orders.created";
                public static final String PAYMENTS_COMPLETED_TOPIC = "payments.completed";

                /** Orders' own consumer. */
                public static final String ORDERS_PROCESSOR_SUB = "orders.created.processor";

                public static String topic(String topicId) {
                    return "projects/" + PROJECT_ID + "/topics/" + topicId;
                }

                public static String subscription(String subscriptionId) {
                    return "projects/" + PROJECT_ID + "/subscriptions/" + subscriptionId;
                }

                @Bean
                public KafkaTemplate<String, String> kafkaTemplate() {
                    return null;
                }

                private PubSubConfig() {
                }
            }
            """;

    private Map<String, String> files(String path, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(path, content);
        return m;
    }

    @Test
    void rewritesToConstantsOnly_keepingNamesValuesAndPrivateCtor() {
        MigratedFile out = anchor.anchor(files("src/main/java/com/example/common/PubSubConfig.java", POLLUTED_CONFIG));

        assertThat(out).isNotNull();
        assertThat(out.originalPath()).isEqualTo("src/main/java/com/example/common/PubSubConfig.java");
        assertThat(out.diffSummary()).isEqualTo(
                "Anchored: constants-only rewrite, Spring/@Bean additions and GCP helper methods removed, "
                        + "prevents LLM from hallucinating duplicate configuration.");

        String content = out.content();

        // Constants preserved verbatim (name + value).
        assertThat(content)
                .contains("public static final String ORDERS_TOPIC = \"orders.created\";")
                .contains("public static final String PAYMENTS_COMPLETED_TOPIC = \"payments.completed\";")
                .contains("public static final String ORDERS_PROCESSOR_SUB = \"orders.created.processor\";")
                .contains("private PubSubConfig() {")
                .contains("package com.example.common;");

        // Everything GCP/Spring stripped.
        assertThat(content)
                .doesNotContain("@Configuration")
                .doesNotContain("@Bean")
                .doesNotContain("import ")
                .doesNotContain("PROJECT_ID")
                .doesNotContain("String topic(")
                .doesNotContain("String subscription(")
                .doesNotContain("projects/")
                .doesNotContain("KafkaTemplate")
                .doesNotContain("com.google");
    }

    @Test
    void rewriteReparsesAndHasExactlyTheConstantsPlusCtor() {
        MigratedFile out = anchor.anchor(files("src/main/java/com/example/common/PubSubConfig.java", POLLUTED_CONFIG));
        assertThat(out).isNotNull();

        CompilationUnit cu = StaticJavaParser.parse(out.content()); // throws if invalid
        ClassOrInterfaceDeclaration cls = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

        assertThat(cu.getImports()).isEmpty();
        assertThat(cls.getAnnotations()).isEmpty();
        assertThat(cls.getMethods()).isEmpty();
        assertThat(cls.getFields()).hasSize(3); // ORDERS_TOPIC, PAYMENTS_COMPLETED_TOPIC, ORDERS_PROCESSOR_SUB
        assertThat(cls.getConstructors()).hasSize(1);
        assertThat(cls.getConstructors().get(0).isPrivate()).isTrue();
    }

    @Test
    void detectsByShape_notByName() {
        String renamed = POLLUTED_CONFIG.replace("PubSubConfig", "MessagingTopics");
        MigratedFile out = anchor.anchor(files("src/main/java/com/example/common/MessagingTopics.java", renamed));

        assertThat(out).isNotNull();
        assertThat(out.content())
                .contains("public class MessagingTopics")
                .contains("private MessagingTopics() {")
                .doesNotContain("@Configuration");
    }

    @Test
    void isIdempotent_onAlreadyPureConstantsClass() {
        // Pure constants class: private ctor, only literal-string constants, no methods.
        // Anchor still fires so the file is kept out of the LLM set (which would otherwise
        // add @Configuration/@Bean on the next retry job). The rewrite is idempotent.
        String pure = """
                package com.example.common;
                public final class KafkaTopics {
                    public static final String ORDERS_TOPIC = "orders.created";
                    private KafkaTopics() {}
                }
                """;
        MigratedFile out = anchor.anchor(files("src/main/java/com/example/common/KafkaTopics.java", pure));
        assertThat(out).isNotNull();
        assertThat(out.content())
                .contains("public static final String ORDERS_TOPIC = \"orders.created\"")
                .contains("private KafkaTopics()")
                .doesNotContain("@Configuration")
                .doesNotContain("@Bean");
    }

    @Test
    void anchors_llmMigratedConfigurationForm_withoutPrivateCtor() {
        // Regression: when the LLM migrates PubSubConfig to @Configuration @Bean it drops
        // the private constructor. The anchor must still fire so the next retry job does not
        // send the file back to the LLM (which would re-add @Configuration and cycle forever).
        // The rewrite also adds a private ctor to the output so the NEXT retry's anchor can
        // re-detect the already-pure form via hasPrivateNoArgCtor.
        String llmMigrated = """
                package com.example.common;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.kafka.core.KafkaAdmin;
                import org.springframework.kafka.core.KafkaTemplate;
                import org.apache.kafka.clients.admin.NewTopic;
                @Configuration
                public class PubSubConfig {
                    public static final String ORDERS_TOPIC = "orders.created";
                    public static final String ORDERS_PROCESSOR_SUB = "orders.created.processor";
                    @Value("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}")
                    private String bootstrapServers;
                    @Bean
                    public KafkaAdmin kafkaAdmin() {
                        return new KafkaAdmin(java.util.Map.of("bootstrap.servers", bootstrapServers));
                    }
                    @Bean
                    public NewTopic ordersTopic() { return new NewTopic(ORDERS_TOPIC, 1, (short) 1); }
                }
                """;
        MigratedFile out = anchor.anchor(files("src/main/java/com/example/common/PubSubConfig.java", llmMigrated));
        assertThat(out).isNotNull();
        String content = out.content();
        // Constants preserved.
        assertThat(content)
                .contains("public static final String ORDERS_TOPIC = \"orders.created\"")
                .contains("public static final String ORDERS_PROCESSOR_SUB = \"orders.created.processor\"");
        // Spring/Bean additions stripped.
        assertThat(content)
                .doesNotContain("@Configuration")
                .doesNotContain("@Bean")
                .doesNotContain("import ")
                .doesNotContain("kafkaAdmin")
                .doesNotContain("bootstrapServers");
        // Private ctor added so the next retry's anchor can re-detect the pure form.
        assertThat(content).contains("private PubSubConfig()");
    }

    @Test
    void returnsNull_whenNoPrivateCtorConstantsHolder() {
        String notAHolder = """
                package com.example;
                public class OrderService {
                    public static final String NAME = "x";
                    public void run() {}
                }
                """;
        assertThat(anchor.anchor(files("src/main/java/com/example/OrderService.java", notAHolder))).isNull();
    }

    @Test
    void returnsNull_onEmptyInput() {
        assertThat(anchor.anchor(Map.of())).isNull();
        assertThat(anchor.anchor(null)).isNull();
    }
}
