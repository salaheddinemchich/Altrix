package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministically identifies a Jakarta EE startup class that bootstraps Pub/Sub
 * topics (the {@code TopicBootstrap} shape) for the {@code SPRING_KAFKA_HYBRID}
 * target, so the migrator can:
 * <ol>
 *   <li>DELETE it — its job (create topics/subscriptions against the Pub/Sub REST
 *       API) is taken over by the {@code KafkaAdmin}+{@code NewTopic} beans the
 *       {@link HybridScaffoldingGenerator} writes into {@code SpringKafkaConfig};
 *       left to the LLM it produced raw {@code AdminClient.create()} boilerplate
 *       and an abstract {@code new KafkaListenerContainerFactory<?>()}, both hard
 *       compile failures; and</li>
 *   <li>harvest the exact set of topic constant simple-names it referenced, which
 *       the generator turns into one {@code NewTopic} bean each (consumers run
 *       with {@code allowAutoTopicCreation=false}, so without explicit topic
 *       beans every listener logs {@code UNKNOWN_TOPIC_OR_PARTITION} forever).</li>
 * </ol>
 *
 * <p><b>Detection is by SHAPE, never by class name</b> (mirrors
 * {@link HybridConsumerTransformer} / {@link TopicSubscriptionBindings}): a
 * top-level class annotated {@code @Singleton} <em>and</em> {@code @Startup} that
 * makes at least one {@code getOrCreateTopic(topic(<CONFIG>.<TOPIC_CONST>))} call.
 * Topic constants are extracted via {@link TopicSubscriptionBindings#constantName}
 * (the same helper that unwraps {@code topic(...)} / qualifier layers the consumer
 * transformer uses), so the two stages agree on constant identity by construction.
 *
 * <p><b>Bail (return {@code null}) when no class matches or no topic constants are
 * found</b> — the file is then left in the LLM's input untouched rather than
 * deleted blindly. Deleting a bootstrap whose topics we could not read would drop
 * the topic-creation beans with no replacement, which is worse than letting the
 * LLM try.
 */
@Slf4j
@Component
public class TopicBootstrapAnchor {

    private static final String GET_OR_CREATE_TOPIC = "getOrCreateTopic";

    /**
     * Find the single Pub/Sub topic-bootstrap class in {@code sourceFiles} and
     * return its topic constants + path, or {@code null} when none is recognized.
     */
    public TopicBootstrapResult analyze(Map<String, String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return null;

        for (Map.Entry<String, String> e : sourceFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            if (!content.contains(GET_OR_CREATE_TOPIC)) continue; // cheap pre-filter

            CompilationUnit cu;
            try {
                cu = new JavaParser().parse(content).getResult().orElse(null);
            } catch (Exception ex) {
                log.debug("[TopicBootstrapAnchor] parse skipped for '{}' ({})", path, ex.getMessage());
                continue;
            }
            if (cu == null) continue;

            Optional<ClassOrInterfaceDeclaration> clsOpt = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.isAnnotationPresent("Singleton") && c.isAnnotationPresent("Startup"));
            if (clsOpt.isEmpty()) continue;
            ClassOrInterfaceDeclaration cls = clsOpt.get();

            // Insertion order = source order → deterministic NewTopic bean order.
            Set<String> topicConstants = new LinkedHashSet<>();
            for (MethodCallExpr call : cls.findAll(MethodCallExpr.class)) {
                if (!GET_OR_CREATE_TOPIC.equals(call.getNameAsString())) continue;
                if (call.getArguments().isEmpty()) continue;
                String topicConst = TopicSubscriptionBindings.constantName(call.getArgument(0));
                if (topicConst != null) topicConstants.add(topicConst);
            }

            if (topicConstants.isEmpty()) {
                log.warn("[TopicBootstrapAnchor] '{}' looks like a @Singleton @Startup bootstrap but no "
                        + "getOrCreateTopic(topic(<CONST>)) constant could be read — leaving it for the LLM", path);
                return null;
            }

            log.info("[TopicBootstrapAnchor] anchored bootstrap '{}' for deletion; harvested {} topic constant(s): {}",
                    path, topicConstants.size(), topicConstants);
            return new TopicBootstrapResult(topicConstants, path);
        }
        return null;
    }
}
