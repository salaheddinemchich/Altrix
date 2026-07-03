package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the {@code {subscription-constant → topic-constant}} mapping from a
 * project's {@code TopicBootstrap}-style class — the authoritative
 * subscription→topic binding a {@link HybridConsumerTransformer} needs to emit
 * {@code @KafkaListener(topics = <TOPIC>, groupId = <SUB>)}.
 *
 * <p>A Pub/Sub consumer method names only the <em>subscription</em> it pulls
 * from (e.g. {@code ORDERS_PROCESSOR_SUB}); the <em>topic</em> that
 * subscription is bound to lives only in the bootstrap class, as
 * {@code pubsub.getOrCreateSubscription(topic(<TOPIC>), subscription(<SUB>), …)}
 * call sites. This resolver parses those call sites and records, per
 * subscription constant, the topic constant it was created against.
 *
 * <p>Constant names are matched by <b>simple name</b> ({@code ORDERS_TOPIC},
 * {@code ORDERS_PROCESSOR_SUB}) regardless of whether they were written bare or
 * qualified ({@code PubSubConfig.ORDERS_TOPIC}) and regardless of whether the
 * argument was wrapped in a {@code topic(...)} / {@code subscription(...)}
 * helper call — the wrapper is unwrapped to reach the underlying constant.
 */
@Slf4j
public final class TopicSubscriptionBindings {

    private final Map<String, String> subToTopic;

    private TopicSubscriptionBindings(Map<String, String> subToTopic) {
        this.subToTopic = subToTopic;
    }

    /** The topic constant simple-name bound to {@code subscriptionConstant}, if any. */
    public Optional<String> topicFor(String subscriptionConstant) {
        return Optional.ofNullable(subToTopic.get(subscriptionConstant));
    }

    public boolean isEmpty() {
        return subToTopic.isEmpty();
    }

    public int size() {
        return subToTopic.size();
    }

    /**
     * Build the bindings from all source files: scans every file for
     * {@code getOrCreateSubscription(<topicArg>, <subArg>, …)} call sites
     * (the {@code TopicBootstrap} shape). Files that don't contain that call
     * contribute nothing. Returns an empty (never null) bindings object when
     * no bootstrap bindings exist — callers treat "subscription has no topic"
     * as a per-method bail reason, never a guess.
     */
    public static TopicSubscriptionBindings from(Map<String, String> sourceFiles) {
        Map<String, String> map = new LinkedHashMap<>();
        if (sourceFiles == null) return new TopicSubscriptionBindings(map);

        for (Map.Entry<String, String> e : sourceFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            if (!content.contains("getOrCreateSubscription")) continue; // cheap pre-filter

            CompilationUnit cu;
            try {
                cu = new JavaParser().parse(content).getResult().orElse(null);
            } catch (Exception ex) {
                log.debug("[TopicSubscriptionBindings] parse skipped for '{}' ({})", path, ex.getMessage());
                continue;
            }
            if (cu == null) continue;

            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!"getOrCreateSubscription".equals(call.getNameAsString())) continue;
                if (call.getArguments().size() < 2) continue;
                String topicConst = constantName(call.getArgument(0));
                String subConst = constantName(call.getArgument(1));
                if (topicConst != null && subConst != null) {
                    map.putIfAbsent(subConst, topicConst);
                }
            }
        }
        return new TopicSubscriptionBindings(map);
    }

    /**
     * Unwraps {@code topic(X)} / {@code subscription(X)} helper calls and any
     * qualifier, returning the underlying constant's simple name — e.g.
     * {@code topic(PubSubConfig.ORDERS_TOPIC)} and {@code PubSubConfig.ORDERS_TOPIC}
     * and {@code ORDERS_TOPIC} all yield {@code "ORDERS_TOPIC"}.
     */
    static String constantName(Expression expr) {
        Expression e = expr;
        // Unwrap a single helper-call layer: topic(...) / subscription(...).
        if (e instanceof MethodCallExpr mce && mce.getArguments().size() == 1) {
            e = mce.getArgument(0);
        }
        if (e instanceof FieldAccessExpr fae) {
            return fae.getNameAsString();      // PubSubConfig.ORDERS_TOPIC → ORDERS_TOPIC
        }
        if (e instanceof NameExpr ne) {
            return ne.getNameAsString();       // bare ORDERS_TOPIC
        }
        return null;
    }
}
