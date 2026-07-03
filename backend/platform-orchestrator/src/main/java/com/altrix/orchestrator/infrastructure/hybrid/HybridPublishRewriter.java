package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-revert deterministic fix for non-consumer files (JAX-RS resources, services)
 * that call {@code pubsub.publish(PubSubConfig.topic(CONST), msg)} in the
 * {@code SPRING_KAFKA_HYBRID} target.
 *
 * <p><b>Problem.</b> {@link PubSubConfigAnchor} strips {@code topic()} and
 * {@code subscription()} from {@code PubSubConfig}, so any file that calls those
 * helpers produces "cannot find symbol" compile errors.  Consumer classes (those
 * with {@code @Schedule} methods) are handled by {@link HybridConsumerTransformer}
 * before the LLM pass.  Non-consumer files that only <em>publish</em> (e.g. JAX-RS
 * resources) are processed by the LLM, but the LLM tends to import non-existent
 * bridge classes ({@code SpringContextBootstrapper}) which then triggers
 * {@code revertFilesWithUnresolvedImports} — restoring the original source, which
 * still has the broken {@code PubSubConfig.topic()} call.
 *
 * <p><b>Fix.</b> After the revert pass, this rewriter finds files whose original
 * source has {@code @Inject PubSubService} + {@code pubsub.publish(PubSubConfig.topic(CONST), msg)}
 * and rewrites them deterministically to:
 * <ul>
 *   <li>replace {@code @Inject PubSubService pubsub} with
 *       {@code @Inject KafkaTemplate<String, String> kafkaTemplate}</li>
 *   <li>replace each {@code pubsub.publish(PubSubConfig.topic(PubSubConfig.CONST), msg)}
 *       call with {@code kafkaTemplate.send(PubSubConfig.CONST, msg)}</li>
 * </ul>
 *
 * <p>{@code KafkaTemplate} is made injectable via CDI by the {@code @Produces @Dependent}
 * method in the generated {@link com.altrix.orchestrator.infrastructure.hybrid.HybridScaffoldingGenerator
 * SpringBeanBridge} bridge class — the same cross-container bridge the
 * {@link HybridConsumerTransformer} relies on.
 *
 * <p>Files with publish patterns that cannot be mapped to a PubSubConfig constant
 * are left unchanged so we never introduce new errors.
 */
@Slf4j
@Component
public class HybridPublishRewriter {

    private static final String PUBSUB_SERVICE_TYPE = "PubSubService";
    private static final String DIFF_SUMMARY =
            "Rewrote PubSubService.publish → KafkaTemplate.send (deterministic hybrid publish rewrite).";

    /**
     * Inspect every file in {@code files}; rewrite those with the recognized
     * {@code @Inject PubSubService} + {@code pubsub.publish(...)} pattern.
     * Returns a new list; original files that cannot be rewritten are kept unchanged.
     */
    public List<MigratedFile> rewrite(List<MigratedFile> files) {
        if (files == null || files.isEmpty()) return files;

        List<MigratedFile> out = new ArrayList<>(files.size());
        for (MigratedFile f : files) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path == null || !path.toLowerCase().endsWith(".java") || f.content() == null) {
                out.add(f);
                continue;
            }
            String content = f.content();
            if (!content.contains(PUBSUB_SERVICE_TYPE) || !content.contains(".publish(")) {
                out.add(f);
                continue;
            }
            MigratedFile rewritten = tryRewrite(f, path, content);
            out.add(rewritten != null ? rewritten : f);
        }
        return out;
    }

    private MigratedFile tryRewrite(MigratedFile original, String path, String content) {
        CompilationUnit cu;
        try {
            cu = new JavaParser().parse(content).getResult().orElse(null);
        } catch (Exception ex) {
            log.debug("[HybridPublishRewriter] parse skipped for '{}' ({})", path, ex.getMessage());
            return null;
        }
        if (cu == null) return null;

        ClassOrInterfaceDeclaration cls = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (cls == null) return null;

        // Find the @Inject PubSubService field name
        String pubsubField = null;
        FieldDeclaration pubsubFieldDecl = null;
        for (FieldDeclaration fd : cls.getFields()) {
            if (!fd.isAnnotationPresent("Inject")) continue;
            if (!PUBSUB_SERVICE_TYPE.equals(simpleType(fd.getElementType().asString()))) continue;
            pubsubField = fd.getVariables().get(0).getNameAsString();
            pubsubFieldDecl = fd;
            break;
        }
        if (pubsubField == null) return null;
        final String fieldName = pubsubField; // effectively-final copy for use in lambdas

        // Collect all publish calls on this field
        List<MethodCallExpr> publishCalls = new ArrayList<>();
        for (MethodCallExpr mc : cu.findAll(MethodCallExpr.class)) {
            if (!mc.getScope().map(s -> fieldName.equals(s.toString())).orElse(false)) continue;
            if (!"publish".equals(mc.getNameAsString())) continue;
            publishCalls.add(mc);
        }
        if (publishCalls.isEmpty()) return null;

        // Validate every publish call can be rewritten BEFORE mutating any
        for (MethodCallExpr mc : publishCalls) {
            if (mc.getArguments().size() < 2) return null;
            String topicConst = TopicSubscriptionBindings.constantName(mc.getArgument(0));
            if (topicConst == null) {
                log.debug("[HybridPublishRewriter] skipping '{}' — could not extract topic constant "
                        + "from publish call arg0", path);
                return null;
            }
        }

        // Mutate: rewrite each publish call to kafkaTemplate.send(PubSubConfig.CONST, msg)
        for (MethodCallExpr mc : publishCalls) {
            String topicConst = TopicSubscriptionBindings.constantName(mc.getArgument(0));
            MethodCallExpr send = new MethodCallExpr();
            send.setScope(new NameExpr("kafkaTemplate"));
            send.setName("send");
            send.addArgument(StaticJavaParser.parseExpression("PubSubConfig." + topicConst));
            send.addArgument(mc.getArgument(1).clone());
            mc.replace(send);
        }

        // Remove the @Inject PubSubService field and its import
        pubsubFieldDecl.remove();
        cu.getImports().removeIf(imp -> imp.getNameAsString().endsWith("." + PUBSUB_SERVICE_TYPE));

        // Add @Inject KafkaTemplate<String, String> kafkaTemplate field
        FieldDeclaration kafkaTemplateField = StaticJavaParser
                .parseBodyDeclaration("@Inject\nKafkaTemplate<String, String> kafkaTemplate;")
                .asFieldDeclaration();
        cls.getMembers().add(0, kafkaTemplateField);

        // Ensure the required imports are present
        boolean hasKafkaTemplateImport = cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().contains("KafkaTemplate"));
        if (!hasKafkaTemplateImport) {
            cu.addImport("org.springframework.kafka.core.KafkaTemplate");
        }
        boolean hasInjectImport = cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals("jakarta.inject.Inject"));
        if (!hasInjectImport) {
            cu.addImport("jakarta.inject.Inject");
        }

        String rewrittenContent = cu.toString();
        // Defensive: never emit something that doesn't parse
        try {
            if (new JavaParser().parse(rewrittenContent).getResult().isEmpty()) {
                log.warn("[HybridPublishRewriter] rewrite of '{}' did not re-parse — leaving original", path);
                return null;
            }
        } catch (Exception ex) {
            log.warn("[HybridPublishRewriter] rewrite of '{}' did not re-parse ({}) — leaving original",
                    path, ex.getMessage());
            return null;
        }

        log.info("[HybridPublishRewriter] rewrote {} publish call(s) in '{}' → kafkaTemplate.send",
                publishCalls.size(), path);
        return MigratedFile.builder()
                .originalPath(original.originalPath())
                .newPath(original.newPath() != null ? original.newPath() : original.originalPath())
                .content(rewrittenContent)
                .changeType(FileChangeType.MODIFIED)
                .diffSummary(DIFF_SUMMARY)
                .build();
    }

    private static String simpleType(String type) {
        int lt = type.indexOf('<');
        String raw = lt >= 0 ? type.substring(0, lt) : type;
        int dot = raw.lastIndexOf('.');
        return (dot >= 0 ? raw.substring(dot + 1) : raw).trim();
    }
}
