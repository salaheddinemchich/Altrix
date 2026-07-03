package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Generates the mandatory Jakarta EE + Spring Kafka hybrid bridge classes that
 * the migration target requires but a genuine Pub/Sub source project has no
 * file to map from.
 *
 * <p><b>This is the ONLY component in the migration pipeline permitted to ADD
 * files to the artifact rather than transform existing ones.</b> Every other
 * stage ({@code revertFilesWithUnresolvedImports}, {@code applyContractRepairs},
 * {@code applyPubSubLeakRepairs}, {@code applyPomDependencyReconciliation},
 * {@code CdiStatelessConverter}) only ever rewrites or reverts files already in
 * the map. That 1-input-file→1-output-file invariant is exactly why a genuine
 * Pub/Sub project — which has no {@code SpringKafkaConfig} / {@code CdiLookup} /
 * {@code SpringContextBootstrapper} / {@code SpringBeanBridge} /
 * {@code AppStartupListener} to start from — failed to compile when migrated to
 * the hybrid target (the listener referenced {@code CdiLookup}, which the
 * migrator could never create). This generator closes that gap deterministically
 * rather than by trusting a free-tier model to freehand structural code with no
 * ground truth to validate against — the same philosophy as
 * {@code PomDependencyReconciler} and {@code CdiStatelessConverter}. The
 * addition of new entries here is intentional; do NOT "fix" it back to
 * transform-only.
 *
 * <p>Four of the five files are byte-for-byte fixed templates
 * ({@code /hybrid-templates/*.java.template}) with only the package substituted.
 * The fifth, {@code SpringKafkaConfig}, is the one file with real per-project
 * content: its {@code @ComponentScan} package list must reflect the actual
 * listener packages in the final, repaired artifact — which is why this runs
 * LAST, after every repair/reconciliation stage, so a file reverted back to
 * non-listener form is not wrongly scanned.
 *
 * <p><b>Empty-listener case:</b> if no {@code @KafkaListener} survives in the
 * final artifact, nothing is generated (verified: the early return below
 * prevents any file insertion). That is itself a signal something upstream went
 * wrong — it is logged loudly here and surfaced as a formal
 * {@code SPRING_KAFKA_NO_LISTENERS_FOUND} finding by {@code SemanticValidatorAgent}
 * (the migrator stage has no findings channel; the validator is where findings
 * are produced).
 *
 * <p><b>Idempotency:</b> if the project already declares a class with one of the
 * five names (e.g. {@code kb-test-jakarta-springkafka}, a hand-written reference
 * that ships the bridge classes as source), that file is left to the normal
 * 1:1 migrator path and NOT duplicated here.
 */
@Slf4j
@Component
public class HybridScaffoldingGenerator {

    private static final String TEMPLATE_DIR = "/hybrid-templates/";

    /** The 4 fixed-content bridge classes (template resource per class). */
    private static final List<String> FIXED_TEMPLATE_CLASSES = List.of(
            "SpringContextBootstrapper",
            "AppStartupListener",
            "SpringBeanBridge",
            "CdiLookup");

    private static final String DIFF_SUMMARY =
            "Generated Spring Kafka hybrid bridge class (deterministic scaffolding — required for the "
                    + "manually-bootstrapped Spring context to coexist with the CDI container).";

    /**
     * The one file with per-project content. {@code ${package}} and
     * {@code ${componentScan}} are this generator's substitution tokens; the
     * {@code ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}} placeholder is a real
     * Spring {@code @Value} placeholder that must survive verbatim into the
     * output (resolved at runtime by the {@code PropertySourcesPlaceholderConfigurer}
     * bean against the OS environment, defaulting to {@code localhost:9092}).
     * No {@code @PropertySource("classpath:application.properties")} — a generated
     * project may not ship one, and depending on it would fail context startup.
     */
    private static final String SPRING_KAFKA_CONFIG_TEMPLATE = """
            package ${package};

            import org.apache.kafka.clients.consumer.ConsumerConfig;
            import org.apache.kafka.clients.producer.ProducerConfig;
            import org.apache.kafka.common.serialization.StringDeserializer;
            import org.apache.kafka.common.serialization.StringSerializer;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.ComponentScan;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
            import org.springframework.kafka.annotation.EnableKafka;
            import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
            import org.springframework.kafka.core.ConsumerFactory;
            import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
            import org.springframework.kafka.core.DefaultKafkaProducerFactory;
            import org.springframework.kafka.core.KafkaTemplate;
            import org.springframework.kafka.core.ProducerFactory;

            import java.util.HashMap;
            import java.util.Map;
            ${adminImports}

            /**
             * Plain Spring Framework config — NOT Spring Boot. {@code @EnableKafka}
             * registers the post-processor that turns the {@code @ComponentScan}-found
             * {@code @KafkaListener} methods into running consumers. Generated
             * deterministically by Altrix's HybridScaffoldingGenerator; the
             * {@code @ComponentScan} list below is the actual set of migrated
             * listener packages.
             */
            @Configuration
            @EnableKafka
            @ComponentScan(basePackages = {
            ${componentScan}})
            public class SpringKafkaConfig {

                @Value("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}")
                private String bootstrapServers;

                /** Required in plain Spring for ${...} @Value placeholders to resolve. */
                @Bean
                public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
                    return new PropertySourcesPlaceholderConfigurer();
                }

                @Bean
                public ProducerFactory<String, String> producerFactory() {
                    Map<String, Object> props = new HashMap<>();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                    return new DefaultKafkaProducerFactory<>(props);
                }

                @Bean
                public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
                    return new KafkaTemplate<>(producerFactory);
                }

                @Bean
                public ConsumerFactory<String, String> consumerFactory() {
                    Map<String, Object> props = new HashMap<>();
                    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                    return new DefaultKafkaConsumerFactory<>(props);
                }

                @Bean
                public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                        ConsumerFactory<String, String> consumerFactory) {
                    ConcurrentKafkaListenerContainerFactory<String, String> factory =
                            new ConcurrentKafkaListenerContainerFactory<>();
                    factory.setConsumerFactory(consumerFactory);
                    return factory;
                }
            ${adminBeans}}
            """;

    /**
     * Adds the 5 hybrid bridge classes to {@code files} when at least one
     * {@code @KafkaListener} is present and the class isn't already declared.
     * Returns {@code files} unchanged when there is nothing to add.
     *
     * <p>{@code topicConstantNames} are the topic-constant simple-names harvested
     * by {@link TopicBootstrapAnchor} from the deleted Pub/Sub bootstrap class.
     * Each becomes one {@code NewTopic} bean (plus a single {@code KafkaAdmin})
     * in the generated {@code SpringKafkaConfig}, so the broker pre-creates every
     * topic the consumers (which run {@code allowAutoTopicCreation=false}) expect.
     * Empty/blank → no admin or topic beans are emitted and the config is rendered
     * exactly as before (the constant holder may legitimately not exist, e.g. unit
     * fixtures), so this stays backward-compatible.
     */
    public List<MigratedFile> generate(List<MigratedFile> files, Set<String> topicConstantNames) {
        if (files == null || files.isEmpty()) return files;

        TreeSet<String> listenerPackages = collectListenerPackages(files);
        if (listenerPackages.isEmpty()) {
            // Verified: returns here BEFORE any file is built — the empty-set
            // case never produces scaffolding. The formal
            // SPRING_KAFKA_NO_LISTENERS_FOUND finding is raised downstream in
            // SemanticValidatorAgent; this loud log makes it non-silent at the
            // migrator stage too.
            log.warn("[HybridScaffoldingGenerator] SPRING_KAFKA_HYBRID selected but the final artifact has no "
                    + "@KafkaListener — generating no bridge classes. Something upstream failed to convert the "
                    + "former Pub/Sub consumers into Spring listeners.");
            return files;
        }

        String basePackage = commonDotPrefix(listenerPackages);
        Map<String, String> toGenerate = new LinkedHashMap<>();

        // SpringKafkaConfig — the per-project file (dynamic @ComponentScan + the
        // KafkaAdmin/NewTopic beans for the topics the deleted bootstrap created).
        if (!anyFileDeclaresClass(files, "SpringKafkaConfig")) {
            toGenerate.put("SpringKafkaConfig",
                    renderSpringKafkaConfig(basePackage, listenerPackages, files, topicConstantNames));
        }
        // The 4 fixed-template files — only the package is substituted.
        for (String className : FIXED_TEMPLATE_CLASSES) {
            if (anyFileDeclaresClass(files, className)) continue;
            toGenerate.put(className, renderTemplate(className, basePackage));
        }

        if (toGenerate.isEmpty()) {
            log.info("[HybridScaffoldingGenerator] all 5 bridge classes already present — nothing to generate");
            return files;
        }

        List<MigratedFile> out = new ArrayList<>(files);
        for (Map.Entry<String, String> e : toGenerate.entrySet()) {
            String path = pathFor(basePackage, e.getKey());
            out.add(MigratedFile.builder()
                    .originalPath(path)
                    .newPath(path)
                    .content(e.getValue())
                    .changeType(FileChangeType.CREATED)
                    .diffSummary(DIFF_SUMMARY)
                    .build());
        }
        log.info("[HybridScaffoldingGenerator] generated {} bridge class(es) in package '{}' "
                        + "(@ComponentScan over {})",
                toGenerate.size(), basePackage, listenerPackages);
        return out;
    }

    /** Distinct packages of every file that actually has a {@code @KafkaListener} annotation. */
    private TreeSet<String> collectListenerPackages(List<MigratedFile> files) {
        TreeSet<String> packages = new TreeSet<>();
        for (MigratedFile f : files) {
            String content = f.content();
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (content == null || !content.contains("@KafkaListener")
                    || path == null || !path.toLowerCase().endsWith(".java")) {
                continue;
            }
            CompilationUnit cu = parseOrNull(content, path);
            if (cu == null) continue;
            boolean hasListener = cu.findFirst(AnnotationExpr.class,
                    a -> "KafkaListener".equals(a.getNameAsString())).isPresent();
            if (!hasListener) continue;
            cu.getPackageDeclaration().ifPresent(pd -> packages.add(pd.getNameAsString()));
        }
        return packages;
    }

    /**
     * Longest common dot-segment prefix of the listener packages — the base
     * package the generated scaffolding lives in. For a single package, that's
     * the package itself. If the packages share no leading segment at all
     * (unusual), falls back to the first package so the files still land
     * somewhere real rather than the default package.
     */
    static String commonDotPrefix(TreeSet<String> packages) {
        if (packages.isEmpty()) return "";
        String first = packages.first();
        if (packages.size() == 1) return first;

        String[] prefix = first.split("\\.");
        int common = prefix.length;
        for (String p : packages) {
            String[] segs = p.split("\\.");
            int i = 0;
            int max = Math.min(common, segs.length);
            while (i < max && prefix[i].equals(segs[i])) i++;
            common = i;
            if (common == 0) break;
        }
        if (common == 0) return first;
        return String.join(".", java.util.Arrays.copyOf(prefix, common));
    }

    private String renderSpringKafkaConfig(String basePackage, TreeSet<String> listenerPackages,
                                           List<MigratedFile> files, Set<String> topicConstantNames) {
        StringBuilder scan = new StringBuilder();
        for (String p : listenerPackages) {
            scan.append("        \"").append(p).append("\",\n");
        }

        // KafkaAdmin + per-topic NewTopic beans. Only emitted when we both have
        // topic constants AND can resolve the class that declares them (to import
        // it and reference <SimpleName>.<CONST>). Either missing → render exactly
        // as before, so projects/fixtures without a topic-constant holder are
        // untouched (the empty-list back-compat contract).
        String adminImports = "";
        String adminBeans = "";
        if (topicConstantNames != null && !topicConstantNames.isEmpty()) {
            Optional<ConfigRef> configRef = resolveConfigClass(files, topicConstantNames);
            if (configRef.isPresent()) {
                adminImports = renderAdminImports(configRef.get(), basePackage);
                adminBeans = renderAdminBeans(configRef.get(), topicConstantNames);
            } else {
                log.warn("[HybridScaffoldingGenerator] {} topic constant(s) supplied but no class declaring them "
                        + "was found in the artifact — emitting no KafkaAdmin/NewTopic beans", topicConstantNames.size());
            }
        }

        return SPRING_KAFKA_CONFIG_TEMPLATE
                .replace("${package}", basePackage)
                .replace("${componentScan}", scan.toString())
                .replace("${adminImports}\n", adminImports)
                .replace("${adminBeans}", adminBeans);
    }

    /** The class that declares the topic constants — its FQN (for the import) and simple name (for references). */
    private record ConfigRef(String fqn, String simpleName, String packageName) {
    }

    /**
     * Locate the class in the artifact that declares the supplied topic constants
     * as {@code static} fields, so the generated config can import it and write
     * {@code <SimpleName>.<CONST>}. Returns the first class declaring any of them.
     */
    private Optional<ConfigRef> resolveConfigClass(List<MigratedFile> files, Set<String> topicConstantNames) {
        for (MigratedFile f : files) {
            String content = f.content();
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (content == null || path == null || !path.toLowerCase().endsWith(".java")) continue;
            CompilationUnit cu = parseOrNull(content, path);
            if (cu == null) continue;
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                for (FieldDeclaration fd : cls.getFields()) {
                    if (!fd.isStatic()) continue;
                    for (VariableDeclarator v : fd.getVariables()) {
                        if (topicConstantNames.contains(v.getNameAsString())) {
                            String simple = cls.getNameAsString();
                            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                            String fqn = pkg.isBlank() ? simple : pkg + "." + simple;
                            return Optional.of(new ConfigRef(fqn, simple, pkg));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String renderAdminImports(ConfigRef configRef, String basePackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("import org.apache.kafka.clients.admin.AdminClientConfig;\n");
        sb.append("import org.apache.kafka.clients.admin.NewTopic;\n");
        sb.append("import org.springframework.kafka.config.TopicBuilder;\n");
        sb.append("import org.springframework.kafka.core.KafkaAdmin;\n");
        // Only import the constant holder when it lives in another, importable package.
        if (!configRef.packageName().isBlank() && !configRef.packageName().equals(basePackage)) {
            sb.append("import ").append(configRef.fqn()).append(";\n");
        }
        return sb.toString();
    }

    private String renderAdminBeans(ConfigRef configRef, Set<String> topicConstantNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("    @Bean\n");
        sb.append("    public KafkaAdmin kafkaAdmin() {\n");
        sb.append("        Map<String, Object> configs = new HashMap<>();\n");
        sb.append("        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);\n");
        sb.append("        return new KafkaAdmin(configs);\n");
        sb.append("    }\n");
        for (String topicConst : topicConstantNames) {
            sb.append("\n");
            sb.append("    @Bean\n");
            sb.append("    public NewTopic ").append(beanName(topicConst)).append("() {\n");
            sb.append("        return TopicBuilder.name(").append(configRef.simpleName()).append(".").append(topicConst)
              .append(").partitions(1).replicas(1).build();\n");
            sb.append("    }\n");
        }
        return sb.toString();
    }

    /** {@code ORDERS_TOPIC} → {@code ordersTopic}; {@code PAYMENTS_COMPLETED_TOPIC} → {@code paymentsCompletedTopic}. */
    static String beanName(String constant) {
        String[] parts = constant.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].toLowerCase();
            if (p.isEmpty()) continue;
            if (sb.length() == 0) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private String renderTemplate(String className, String basePackage) {
        String resource = TEMPLATE_DIR + className + ".java.template";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing hybrid template resource: " + resource);
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return raw.replace("${package}", basePackage);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read hybrid template: " + resource, e);
        }
    }

    private static String pathFor(String basePackage, String className) {
        return "src/main/java/" + basePackage.replace('.', '/') + "/" + className + ".java";
    }

    /** True when some file in the artifact already declares a top-level class/interface/enum with this name. */
    private static boolean anyFileDeclaresClass(List<MigratedFile> files, String className) {
        Pattern decl = Pattern.compile("\\b(class|interface|enum)\\s+" + Pattern.quote(className) + "\\b");
        for (MigratedFile f : files) {
            String content = f.content();
            if (content == null) continue;
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path == null || !path.toLowerCase().endsWith(".java")) continue;
            if (decl.matcher(content).find()) return true;
        }
        return false;
    }

    private CompilationUnit parseOrNull(String content, String path) {
        try {
            Optional<CompilationUnit> cu = new JavaParser().parse(content).getResult();
            return cu.orElse(null);
        } catch (Exception e) {
            log.debug("[HybridScaffoldingGenerator] parse skipped for '{}' ({})", path, e.getMessage());
            return null;
        }
    }
}
