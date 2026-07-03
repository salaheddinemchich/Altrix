package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid-only (Jakarta EE + Spring Kafka), detect-only check for the
 * "target-pattern drift" that caused root cause 2 of job {@code 2c91a1fc}:
 * within a single hybrid migration, some files drift toward the RAW
 * kafka-clients idiom instead of the hybrid target's
 * {@code KafkaTemplate} / {@code @KafkaListener} idiom.
 *
 * <p><b>Why detect-only, not auto-fix.</b> Rewriting a class that builds a
 * {@code Properties} map and constructs a {@code KafkaProducer}/{@code KafkaConsumer}
 * into {@code KafkaTemplate.send(...)} + {@code @KafkaListener} methods is
 * exactly the kind of creative, multi-line transformation that the free-tier
 * per-file model gets wrong — auto-fixing it mechanically would risk
 * reproducing the very hallucination this whole change exists to defend
 * against. So, like {@code SpringKafkaOverEngineeringDetector}, this surfaces a
 * precise, actionable instruction for the AI repair tier instead.
 *
 * <p><b>What it flags</b> (only when the target is {@code SPRING_KAFKA_HYBRID}):
 * <ul>
 *   <li>direct construction of {@code KafkaProducer} / {@code KafkaConsumer};</li>
 *   <li>{@code AdminClient.create(...)};</li>
 *   <li>calls to {@code producerProperties()} / {@code consumerProperties()} /
 *       {@code adminProperties()} — the exact symptom that broke job
 *       {@code 2c91a1fc} (a {@code Properties}-returning method the hybrid
 *       target has no reason to need, since {@code KafkaTemplate} /
 *       {@code @KafkaListener} never touch raw {@code Properties} directly).</li>
 * </ul>
 *
 * <p>The 5 deterministically-generated scaffolding classes are excluded by
 * simple name — {@code SpringKafkaConfig} legitimately builds
 * {@code DefaultKafkaProducerFactory}/{@code DefaultKafkaConsumerFactory} from
 * config maps, which is correct for the hybrid bridge and must not be flagged.
 */
@Slf4j
@Component
public class SpringKafkaTargetConformanceDetector {

    /** The deterministically-generated bridge classes — never flagged. */
    private static final Set<String> SCAFFOLD_CLASS_NAMES = Set.of(
            "SpringKafkaConfig", "SpringContextBootstrapper", "AppStartupListener",
            "SpringBeanBridge", "CdiLookup");

    /** Raw-client types whose direct construction is the drift smell. */
    private static final Set<String> RAW_CLIENT_TYPES = Set.of("KafkaProducer", "KafkaConsumer");

    /** {@code Properties}-returning config methods the hybrid idiom never needs. */
    private static final Set<String> FORBIDDEN_PROPERTIES_METHODS = Set.of(
            "producerProperties", "consumerProperties", "adminProperties");

    public List<Detection> detect(Map<String, String> javaFiles) {
        List<Detection> out = new ArrayList<>();
        if (javaFiles == null || javaFiles.isEmpty()) return out;

        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            // Cheap pre-filter — only files mentioning a raw client or a *Properties() call can match.
            if (!content.contains("KafkaProducer") && !content.contains("KafkaConsumer")
                    && !content.contains("AdminClient") && !content.contains("Properties()")) {
                continue;
            }
            if (isScaffoldFile(content)) continue;
            try {
                out.addAll(detectOne(path, content));
            } catch (Exception ex) {
                log.debug("Target-conformance detection skipped for '{}' ({})", path, ex.getMessage());
            }
        }
        return out;
    }

    private List<Detection> detectOne(String path, String content) {
        List<Detection> out = new ArrayList<>();
        CompilationUnit cu = new JavaParser().parse(content).getResult().orElse(null);
        if (cu == null) return out;

        // (a) `new KafkaProducer<>(...)` / `new KafkaConsumer<>(...)`
        for (ObjectCreationExpr expr : cu.findAll(ObjectCreationExpr.class)) {
            String simple = simpleName(expr.getType().getNameAsString());
            if (RAW_CLIENT_TYPES.contains(simple)) {
                out.add(new Detection(path, line(expr), "new " + simple,
                        instruction(path, "constructs a raw " + simple + " directly")));
            }
        }
        // (b) `AdminClient.create(...)` and (c) `*.producerProperties()` / etc.
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if ("create".equals(name)
                    && call.getScope().map(s -> "AdminClient".equals(s.toString())).orElse(false)) {
                out.add(new Detection(path, line(call), "AdminClient.create",
                        instruction(path, "calls AdminClient.create(...) directly")));
            } else if (FORBIDDEN_PROPERTIES_METHODS.contains(name)) {
                out.add(new Detection(path, line(call), name + "()",
                        instruction(path, "calls " + name + "() to build raw Kafka client Properties")));
            }
        }
        return out;
    }

    private static String instruction(String path, String offending) {
        String cls = classNameFromPath(path);
        return "This project's migration target is Spring Kafka hybrid. " + cls + " " + offending
                + ". Do NOT construct KafkaProducer/KafkaConsumer/AdminClient directly or call "
                + "producerProperties()/consumerProperties()/adminProperties(). For publishing, @Inject "
                + "KafkaTemplate<String, String> (the SpringBeanBridge-exposed bean) and call "
                + "kafkaTemplate.send(topic, payload). For consuming, use @KafkaListener-annotated methods on a "
                + "@Component class (reaching CDI state via CdiLookup.get(...)), never a hand-built KafkaConsumer.";
    }

    private boolean isScaffoldFile(String content) {
        for (String name : SCAFFOLD_CLASS_NAMES) {
            if (content.contains("class " + name)) return true;
        }
        return false;
    }

    private static int line(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(-1);
    }

    private static String simpleName(String type) {
        int lt = type.indexOf('<');
        String raw = lt >= 0 ? type.substring(0, lt) : type;
        int dot = raw.lastIndexOf('.');
        return (dot >= 0 ? raw.substring(dot + 1) : raw).trim();
    }

    private static String classNameFromPath(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
    }

    /** One target-drift smell, located precisely for the AI repair tier. */
    public record Detection(String filePath, int line, String symbol, String message)
            implements Serializable {
        public Detection {
            if (symbol == null) symbol = "";
            if (message == null) message = "";
        }
    }
}
