package com.altrix.orchestrator.infrastructure.semantic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects — but does NOT auto-fix — the "over-engineered Spring-Kafka consumer"
 * anti-pattern the migrator produces (the bd4e0ba5 {@code OrderSubscriber} bug):
 * a class that uses {@code @KafkaListener} AND hand-wires the listener container /
 * consumer factory that Spring Boot already auto-configures from
 * {@code spring.kafka.*}.
 *
 * <pre>{@code
 * @Service
 * public class OrderSubscriber {
 *     @Autowired private KafkaMessageListenerContainer container;  // no such bean → boot fails
 *     @Bean public ConsumerFactory<...> consumerFactory() { ... }  // duplicates auto-config
 *     @KafkaListener(topics = "...") public void handle(String m) { ... }
 * }
 * }</pre>
 *
 * <p><b>Why detect-only.</b>  Auto-deleting fields / {@code @Bean} methods is too
 * risky (the manual wiring may be load-bearing in non-trivial cases).  Instead we
 * emit a precise {@link Detection} so the AI repair / retry tier gets a surgical
 * instruction ("remove the manual container; {@code @KafkaListener} is
 * self-managed") rather than a vague boot timeout.
 *
 * <p><b>Coexistence guard.</b>  A class is only flagged when {@code @KafkaListener}
 * AND manual wiring appear together.  A class that uses a manual container as its
 * ONLY consumer mechanism (no {@code @KafkaListener}) is a legitimate, if verbose,
 * design and is left alone.
 */
@Slf4j
@Component
public class SpringKafkaOverEngineeringDetector {

    /**
     * Spring-Kafka infrastructure types that Boot auto-configures.  Declaring a
     * field of one of these alongside {@code @KafkaListener} is the smell.  Simple
     * names so we match regardless of import vs. FQN usage.
     */
    private static final Set<String> MANAGED_CONTAINER_TYPES = Set.of(
            "KafkaMessageListenerContainer",
            "ConcurrentMessageListenerContainer",
            "MessageListenerContainer",
            "ContainerProperties");

    /**
     * Return types of {@code @Bean} factory methods that duplicate Boot auto-config
     * when declared inside a {@code @KafkaListener} consumer class.
     */
    private static final Set<String> REDUNDANT_BEAN_FACTORY_TYPES = Set.of(
            "ConsumerFactory",
            "DefaultKafkaConsumerFactory",
            "KafkaListenerContainerFactory",
            "ConcurrentKafkaListenerContainerFactory");

    public List<Detection> detect(Map<String, String> javaFiles) {
        List<Detection> out = new ArrayList<>();
        if (javaFiles == null || javaFiles.isEmpty()) return out;
        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            if (!content.contains("@KafkaListener")) continue; // cheap pre-filter
            try {
                out.addAll(detectOne(path, content));
            } catch (Exception ex) {
                log.debug("Over-engineering detection skipped for '{}' ({})", path, ex.getMessage());
            }
        }
        return out;
    }

    private List<Detection> detectOne(String path, String content) {
        List<Detection> out = new ArrayList<>();
        CompilationUnit cu = new JavaParser().parse(content).getResult().orElse(null);
        if (cu == null) return out;

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean hasKafkaListener = cls.getMethods().stream()
                    .anyMatch(m -> m.isAnnotationPresent("KafkaListener"));
            if (!hasKafkaListener) continue; // coexistence guard

            // (a) fields typed as a Spring-managed container
            for (FieldDeclaration fd : cls.getFields()) {
                String type = fd.getElementType().asString();
                String simple = simpleName(type);
                if (MANAGED_CONTAINER_TYPES.contains(simple)) {
                    String fieldName = fd.getVariables().stream()
                            .map(VariableDeclarator::getNameAsString).findFirst().orElse(simple);
                    out.add(new Detection(path, fd.getBegin().map(p -> p.line).orElse(-1), simple,
                            "Class '" + cls.getNameAsString() + "' uses @KafkaListener but also declares a manual '"
                                    + simple + "' field ('" + fieldName + "'). Spring Boot does not expose a "
                                    + simple + " bean for injection — this fails at startup with "
                                    + "UnsatisfiedDependencyException. Remove the field (and any @PostConstruct that "
                                    + "only starts/stops it); @KafkaListener is self-managed by the auto-configured "
                                    + "listener container."));
                }
            }

            // (b) @Bean factory methods that duplicate Boot auto-config
            for (MethodDeclaration md : cls.getMethods()) {
                if (!md.isAnnotationPresent("Bean")) continue;
                String simple = simpleName(md.getType().asString());
                if (REDUNDANT_BEAN_FACTORY_TYPES.contains(simple)) {
                    out.add(new Detection(path, md.getBegin().map(p -> p.line).orElse(-1), simple,
                            "Class '" + cls.getNameAsString() + "' uses @KafkaListener but also declares a redundant @Bean "
                                    + md.getNameAsString() + "() returning '" + simple + "'. Spring Boot already "
                                    + "auto-configures this from spring.kafka.consumer.* — the manual @Bean duplicates / "
                                    + "overrides it and hardcodes settings. Delete it; rely on application.yml. Reduce the "
                                    + "class to a single @KafkaListener method."));
                }
            }
        }
        return out;
    }

    private static String simpleName(String type) {
        // Strip generics + package: "org.springframework...ConsumerFactory<String,String>" → "ConsumerFactory"
        int lt = type.indexOf('<');
        String raw = lt >= 0 ? type.substring(0, lt) : type;
        int dot = raw.lastIndexOf('.');
        return (dot >= 0 ? raw.substring(dot + 1) : raw).trim();
    }

    /** One over-engineering smell, located precisely for the AI repair tier. */
    public record Detection(String filePath, int line, String symbol, String message)
            implements Serializable {
        public Detection {
            if (symbol == null) symbol = "";
            if (message == null) message = "";
        }
    }
}
