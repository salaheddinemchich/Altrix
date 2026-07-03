package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generator is the ONLY pipeline stage that adds files. These tests pin:
 * (1) all 5 bridge files are generated for a genuine Pub/Sub project,
 * (2) {@code SpringKafkaConfig}'s {@code @ComponentScan} lists exactly the
 *     listener packages, sorted, and the base package is their shortest common
 *     prefix, (3) the empty-listener case adds nothing, (4) idempotency when a
 *     class already exists, and (5) golden-file equality of the 4 fixed
 *     templates so an accidental template edit is caught immediately.
 */
class HybridScaffoldingGeneratorTest {

    private final HybridScaffoldingGenerator generator = new HybridScaffoldingGenerator();

    /** Most tests don't exercise topic beans — no constants supplied. */
    private static final Set<String> NO_TOPICS = Set.of();

    private static final List<String> ALL_FIVE = List.of(
            "SpringKafkaConfig", "SpringContextBootstrapper",
            "AppStartupListener", "SpringBeanBridge", "CdiLookup");

    private MigratedFile listener(String pkg, String className) {
        String content = "package " + pkg + ";\n"
                + "import org.springframework.kafka.annotation.KafkaListener;\n"
                + "import org.springframework.stereotype.Component;\n"
                + "@Component\n"
                + "public class " + className + " {\n"
                + "    @KafkaListener(topics = \"t\")\n"
                + "    public void handle(String m) {}\n"
                + "}\n";
        String path = "src/main/java/" + pkg.replace('.', '/') + "/" + className + ".java";
        return file(path, content);
    }

    private MigratedFile file(String path, String content) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.MODIFIED).diffSummary("x").build();
    }

    private Optional<MigratedFile> generated(List<MigratedFile> out, String className) {
        return out.stream()
                .filter(f -> f.changeType() == FileChangeType.CREATED)
                .filter(f -> f.newPath().endsWith("/" + className + ".java"))
                .findFirst();
    }

    @Test
    void generatesAllFiveBridgeFiles_forSingleListenerPackage() {
        List<MigratedFile> out = generator.generate(
                List.of(listener("com.example.orders", "OrderListener")), NO_TOPICS);

        for (String cls : ALL_FIVE) {
            assertThat(generated(out, cls))
                    .as("generated " + cls)
                    .isPresent();
        }
        // base package = the single listener package
        assertThat(generated(out, "CdiLookup").orElseThrow().newPath())
                .isEqualTo("src/main/java/com/example/orders/CdiLookup.java");
        // @ComponentScan lists exactly that one package
        assertThat(generated(out, "SpringKafkaConfig").orElseThrow().content())
                .contains("@ComponentScan(basePackages = {")
                .contains("\"com.example.orders\",");
    }

    @Test
    void componentScanListsBothPackagesSorted_andBaseIsShortestCommonPrefix() {
        List<MigratedFile> out = generator.generate(List.of(
                listener("com.example.payments", "PaymentListener"),
                listener("com.example.orders", "OrderListener")), NO_TOPICS);

        String config = generated(out, "SpringKafkaConfig").orElseThrow().content();
        // sorted: orders before payments
        int orders = config.indexOf("\"com.example.orders\"");
        int payments = config.indexOf("\"com.example.payments\"");
        assertThat(orders).isGreaterThan(-1);
        assertThat(payments).isGreaterThan(orders);

        // base package = shortest common prefix = com.example
        assertThat(generated(out, "CdiLookup").orElseThrow().newPath())
                .isEqualTo("src/main/java/com/example/CdiLookup.java");
        assertThat(generated(out, "SpringContextBootstrapper").orElseThrow().content())
                .startsWith("package com.example;");
    }

    @Test
    void addsNothing_whenNoListenerPresent() {
        List<MigratedFile> in = List.of(file("src/main/java/p/Plain.java",
                "package p;\npublic class Plain {}\n"));

        List<MigratedFile> out = generator.generate(in, NO_TOPICS);

        assertThat(out).isEqualTo(in);
        assertThat(out).noneMatch(f -> f.changeType() == FileChangeType.CREATED);
    }

    @Test
    void doesNotDuplicate_whenBridgeClassAlreadyDeclared() {
        // A project that already ships CdiLookup as source (e.g. the hand-written
        // kb-test-jakarta-springkafka reference) must not get a second one.
        MigratedFile existingCdiLookup = file("src/main/java/com/example/config/CdiLookup.java",
                "package com.example.config;\npublic final class CdiLookup { }\n");
        List<MigratedFile> out = generator.generate(List.of(
                listener("com.example.orders", "OrderListener"), existingCdiLookup), NO_TOPICS);

        long cdiLookupCount = out.stream()
                .filter(f -> f.newPath().endsWith("/CdiLookup.java")).count();
        assertThat(cdiLookupCount).isEqualTo(1); // only the pre-existing one
        assertThat(generated(out, "CdiLookup")).isEmpty(); // none newly CREATED
        // the other four are still generated
        assertThat(generated(out, "SpringBeanBridge")).isPresent();
    }

    @Test
    void fixedTemplatesRenderByteForByteFromTemplateSource_moduloPackage() throws IOException {
        List<MigratedFile> out = generator.generate(
                List.of(listener("com.example.orders", "OrderListener")), NO_TOPICS);

        for (String cls : List.of("SpringContextBootstrapper", "AppStartupListener",
                "SpringBeanBridge", "CdiLookup")) {
            String expected = templateResource(cls).replace("${package}", "com.example.orders");
            String actual = generated(out, cls).orElseThrow().content();
            assertThat(actual).as("golden render of " + cls).isEqualTo(expected);
        }
    }

    /** A PubSubConfig-shaped constants holder declaring the topic constants. */
    private MigratedFile configHolder() {
        String content = "package com.example.common;\n"
                + "public final class PubSubConfig {\n"
                + "    public static final String ORDERS_TOPIC = \"orders.created\";\n"
                + "    public static final String PAYMENTS_COMPLETED_TOPIC = \"payments.completed\";\n"
                + "    private PubSubConfig() {}\n"
                + "}\n";
        return file("src/main/java/com/example/common/PubSubConfig.java", content);
    }

    @Test
    void generatesKafkaAdminAndNewTopicBeans_whenTopicConstantsSupplied() {
        // Ordered set → deterministic NewTopic bean order (ORDERS then PAYMENTS_COMPLETED).
        Set<String> topics = new java.util.LinkedHashSet<>(List.of("ORDERS_TOPIC", "PAYMENTS_COMPLETED_TOPIC"));
        List<MigratedFile> out = generator.generate(
                List.of(listener("com.example.orders", "OrderListener"), configHolder()), topics);

        String config = generated(out, "SpringKafkaConfig").orElseThrow().content();

        // Admin imports (exact lines).
        assertThat(config)
                .contains("import org.apache.kafka.clients.admin.AdminClientConfig;")
                .contains("import org.apache.kafka.clients.admin.NewTopic;")
                .contains("import org.springframework.kafka.config.TopicBuilder;")
                .contains("import org.springframework.kafka.core.KafkaAdmin;")
                .contains("import com.example.common.PubSubConfig;");

        // KafkaAdmin bean (exact body).
        assertThat(config).contains(
                "    @Bean\n"
                + "    public KafkaAdmin kafkaAdmin() {\n"
                + "        Map<String, Object> configs = new HashMap<>();\n"
                + "        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);\n"
                + "        return new KafkaAdmin(configs);\n"
                + "    }\n");

        // One NewTopic bean per topic, named camelCase from the constant, in order.
        assertThat(config).contains(
                "    @Bean\n"
                + "    public NewTopic ordersTopic() {\n"
                + "        return TopicBuilder.name(PubSubConfig.ORDERS_TOPIC).partitions(1).replicas(1).build();\n"
                + "    }\n");
        assertThat(config).contains(
                "    @Bean\n"
                + "    public NewTopic paymentsCompletedTopic() {\n"
                + "        return TopicBuilder.name(PubSubConfig.PAYMENTS_COMPLETED_TOPIC).partitions(1).replicas(1).build();\n"
                + "    }\n");
        int orders = config.indexOf("ordersTopic()");
        int payments = config.indexOf("paymentsCompletedTopic()");
        assertThat(orders).isGreaterThan(-1);
        assertThat(payments).isGreaterThan(orders);

        // The generated config must itself parse.
        assertThat(com.github.javaparser.StaticJavaParser.parse(config)).isNotNull();
    }

    @Test
    void emitsNoAdminBeans_whenNoTopicConstantsSupplied() {
        String config = generated(generator.generate(
                List.of(listener("com.example.orders", "OrderListener"), configHolder()), NO_TOPICS),
                "SpringKafkaConfig").orElseThrow().content();

        assertThat(config)
                .doesNotContain("KafkaAdmin")
                .doesNotContain("NewTopic")
                .doesNotContain("TopicBuilder")
                .doesNotContain("${adminImports}")
                .doesNotContain("${adminBeans}");
    }

    @Test
    void emitsNoAdminBeans_whenConstantHolderClassAbsent() {
        // Topic constants supplied but no class in the artifact declares them →
        // can't write PubSubConfig.<CONST>, so skip admin beans rather than emit
        // an unresolvable reference.
        Set<String> topics = Set.of("ORDERS_TOPIC");
        String config = generated(generator.generate(
                List.of(listener("com.example.orders", "OrderListener")), topics),
                "SpringKafkaConfig").orElseThrow().content();

        assertThat(config)
                .doesNotContain("KafkaAdmin")
                .doesNotContain("NewTopic")
                .doesNotContain("${adminImports}")
                .doesNotContain("${adminBeans}");
    }

    @Test
    void beanName_camelCasesFromConstant() {
        assertThat(HybridScaffoldingGenerator.beanName("ORDERS_TOPIC")).isEqualTo("ordersTopic");
        assertThat(HybridScaffoldingGenerator.beanName("PAYMENTS_COMPLETED_TOPIC"))
                .isEqualTo("paymentsCompletedTopic");
        assertThat(HybridScaffoldingGenerator.beanName("PAYMENTS_REFUNDED_TOPIC"))
                .isEqualTo("paymentsRefundedTopic");
    }

    @Test
    void commonDotPrefix_singlePackage_returnsItself() {
        assertThat(HybridScaffoldingGenerator.commonDotPrefix(new TreeSet<>(List.of("com.example.orders"))))
                .isEqualTo("com.example.orders");
    }

    @Test
    void commonDotPrefix_segmentAware_notCharacterPrefix() {
        // character prefix of "com.example.orders"/"com.example.ordering" would be
        // "com.example.order" — must trim to the last whole segment: "com.example".
        TreeSet<String> pkgs = new TreeSet<>(List.of("com.example.orders", "com.example.ordering"));
        assertThat(HybridScaffoldingGenerator.commonDotPrefix(pkgs)).isEqualTo("com.example");
    }

    @Test
    void commonDotPrefix_noSharedSegment_fallsBackToFirst() {
        TreeSet<String> pkgs = new TreeSet<>(List.of("com.foo", "org.bar"));
        assertThat(HybridScaffoldingGenerator.commonDotPrefix(pkgs)).isEqualTo("com.foo");
    }

    private String templateResource(String className) throws IOException {
        String resource = "/hybrid-templates/" + className + ".java.template";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("template resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
