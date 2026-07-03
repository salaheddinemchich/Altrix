package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid-only, detect-only counterpart to {@link HybridConsumerTransformer}.
 * Re-detects, from the final artifact, the two consumer-conversion gaps the
 * transformer deliberately leaves for a targeted LLM retry — surfacing each as
 * a {@code SPRING_KAFKA_CONSUMER_NOT_CONVERTED} finding with a precise, mechanical
 * instruction (the same detect-and-retry tier as
 * {@code SpringKafkaTargetConformanceDetector}).
 *
 * <p>Findings are produced here (in the validator) rather than passed from the
 * migrator, matching how every other semantic finding is generated — the
 * artifact itself carries everything needed to regenerate the instruction.
 *
 * <ul>
 *   <li><b>Category-C publish gap</b>: a {@code @KafkaListener} method whose body
 *       still calls {@code <field>.publish(<topic>, <expr>)}. The instruction
 *       names the method, the topic argument, and the exact expression, and asks
 *       for ONLY that call to become {@code kafkaTemplate.send(...)}.</li>
 *   <li><b>Unconverted poller</b>: a method still calling {@code <field>.consume(...)}
 *       — the class was bailed to the LLM (shape mismatch / missing topic
 *       binding); flag it so a zero-progress run is never silent.</li>
 * </ul>
 */
@Slf4j
@Component
public class HybridConsumerConversionDetector {

    public List<Detection> detect(Map<String, String> javaFiles) {
        List<Detection> out = new ArrayList<>();
        if (javaFiles == null || javaFiles.isEmpty()) return out;

        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            boolean maybePublishGap = content.contains("@KafkaListener") && content.contains(".publish(");
            boolean maybeUnconverted = content.contains(".consume(");
            if (!maybePublishGap && !maybeUnconverted) continue;
            try {
                out.addAll(detectOne(path, content));
            } catch (Exception ex) {
                log.debug("Consumer-conversion detection skipped for '{}' ({})", path, ex.getMessage());
            }
        }
        return out;
    }

    private List<Detection> detectOne(String path, String content) {
        List<Detection> out = new ArrayList<>();
        CompilationUnit cu = new JavaParser().parse(content).getResult().orElse(null);
        if (cu == null) return out;

        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            boolean isListener = m.isAnnotationPresent("KafkaListener");

            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                if (isListener && "publish".equals(name) && call.getArguments().size() == 2) {
                    // Category-C publish gap — targeted instruction.
                    Expression topic = call.getArgument(0);
                    Expression msg = call.getArgument(1);
                    out.add(new Detection(path, line(call), m.getNameAsString() + " (publish)",
                            "This class's '" + m.getNameAsString() + "' method has already been converted to a "
                                    + "@KafkaListener except for one call: " + call + ". Rewrite ONLY that call to "
                                    + "kafkaTemplate.send(" + topic + ", " + msg + ") using the already-@Autowired "
                                    + "KafkaTemplate<String, String> kafkaTemplate field. Do not modify any other "
                                    + "part of this method or class."));
                } else if (!isListener && "consume".equals(name)) {
                    // Unconverted poller — class was bailed to the LLM.
                    out.add(new Detection(path, line(call), m.getNameAsString() + " (consume)",
                            "Method '" + m.getNameAsString() + "' still calls a Pub/Sub-style consume(...) and was "
                                    + "not converted to a Spring @KafkaListener. Convert it: make the class a "
                                    + "@Component, replace this @Schedule poll method with a @KafkaListener(topics=..., "
                                    + "groupId=...) method taking a single String payload, and reach any CDI bean via "
                                    + "CdiLookup.get(X.class) (never @Inject)."));
                }
            }
        }
        return out;
    }

    private static int line(MethodCallExpr call) {
        return call.getBegin().map(p -> p.line).orElse(-1);
    }

    /** One consumer-conversion gap, located for the AI repair tier. */
    public record Detection(String filePath, int line, String symbol, String message)
            implements Serializable {
        public Detection {
            if (symbol == null) symbol = "";
            if (message == null) message = "";
        }
    }
}
