package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic, source-in transform of Jakarta EE Pub/Sub <b>consumers</b>
 * into Spring {@code @KafkaListener @Component} classes for the
 * {@code SPRING_KAFKA_HYBRID} target — the piece that makes the migration
 * coherent regardless of the LLM, since two capable models both produced
 * mutually-incompatible per-file output here (raw {@code KafkaConsumer} in one
 * file, the kept seam in another, inconsistent constant renames across files).
 *
 * <p><b>This runs source-in, BEFORE the LLM per-file pass</b> ({@code
 * CoreMigratorAgent.execute}, before {@code migrateFiles}): recognized consumer
 * classes are transformed here and REMOVED from the LLM's input entirely, so
 * the model never gets the chance to invent divergent structure for them. That
 * is a different insertion point than every repair-layer class (which
 * post-processes already-migrated output); like {@code HybridScaffoldingGenerator}
 * it is a deliberate exception worth calling out loudly.
 *
 * <p><b>Category scope (A + B deterministic; C structural + publish-call
 * deferred).</b> Each recognized consumer method is classified by what its
 * per-message body touches:
 * <ul>
 *   <li><b>A</b> — body only logs / parses the payload → lifted verbatim.</li>
 *   <li><b>B</b> — body reaches exactly one {@code @Inject}ed CDI field via
 *       method calls → lifted, with that field replaced by a
 *       {@code CdiLookup.get(T.class)} local (CDI state can't be {@code @Inject}ed
 *       into a Spring bean). Same mechanical certainty as
 *       {@code CdiStatelessConverter}.</li>
 *   <li><b>C</b> — body also re-publishes via {@code pubsub.publish(PubSubConfig.topic(CONST), msg)}
 *       (or touches multiple CDI fields / uses one non-trivially). The structure is
 *       converted deterministically (annotation, signature, CdiLookup bridging)
 *       AND the {@code pubsub.publish(PubSubConfig.topic(CONST), msg)} call is
 *       <em>also</em> rewritten to {@code kafkaTemplate.send(PubSubConfig.CONST, msg)}:
 *       the topic constant is extracted from the {@code topic(...)} wrapper and an
 *       {@code @Autowired KafkaTemplate<String, String> kafkaTemplate} field is
 *       added. This eliminates the compile gap for the common pattern where the
 *       topic constant is statically known.</li>
 * </ul>
 *
 * <p><b>Why C does a full deterministic rewrite rather than leaving a gap.</b>
 * {@link PubSubConfigAnchor} strips the {@code topic()} helper from PubSubConfig
 * so the original {@code pubsub.publish(PubSubConfig.topic(CONST), msg)} would
 * produce "cannot find symbol: topic()" regardless; leaving it in place produces
 * a guaranteed compile failure that is harder to retry than a clean
 * {@code kafkaTemplate.send(CONST, msg)} call.
 *
 * <p><b>Class-level bail (the genuinely class-wide case).</b> If any
 * {@code @Schedule} method in a consumer class does not match the recognized
 * shape, or its subscription has no topic binding in {@code TopicBootstrap}
 * ({@link TopicSubscriptionBindings}), the whole class is left untouched for the
 * LLM — never half-converted — because the same one-stereotype-per-class
 * constraint makes a partial conversion incoherent.
 */
@Slf4j
@Component
public class HybridConsumerTransformer {

    private static final String PUBSUB_SERVICE_TYPE = "PubSubService";
    private static final String CONFIG_TYPE = "PubSubConfig";

    /** Outcome of a transform pass. */
    public record Result(List<MigratedFile> convertedFiles, List<Bail> bails) {
        public Result {
            convertedFiles = convertedFiles != null ? List.copyOf(convertedFiles) : List.of();
            bails = bails != null ? List.copyOf(bails) : List.of();
        }

        public Set<String> convertedPaths() {
            Set<String> paths = new LinkedHashSet<>();
            for (MigratedFile f : convertedFiles) paths.add(f.originalPath());
            return paths;
        }
    }

    /** A class left for the LLM, with a human-distinguishable reason. */
    public record Bail(String filePath, String className, String reason) {
    }

    private static final String DIFF_SUMMARY =
            "Converted Pub/Sub consumer to Spring @KafkaListener (deterministic hybrid transform).";

    /**
     * Transform every recognized consumer source file into a
     * {@code @KafkaListener @Component} class. {@code sourceFiles} is the source
     * map (path → content); returns the converted files (to add to the artifact
     * and remove from the LLM's input) plus the bails (classes left for the LLM).
     */
    public Result transform(Map<String, String> sourceFiles) {
        List<MigratedFile> converted = new ArrayList<>();
        List<Bail> bails = new ArrayList<>();
        if (sourceFiles == null || sourceFiles.isEmpty()) return new Result(converted, bails);

        TopicSubscriptionBindings bindings = TopicSubscriptionBindings.from(sourceFiles);

        // First pass: find every convertible consumer class + its package, so the
        // generated CdiLookup import package matches HybridScaffoldingGenerator's
        // base package (shortest common prefix of all listener packages).
        Map<String, ParsedConsumer> consumers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sourceFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;
            if (!content.contains("@Schedule") || !content.contains(".consume(")) continue; // cheap pre-filter
            ParsedConsumer pc = parseConsumerClass(path, content, bindings);
            if (pc == null) continue; // not a recognized consumer-shaped class at all
            if (pc.bailReason != null) {
                bails.add(new Bail(path, pc.className, pc.bailReason));
                continue;
            }
            consumers.put(path, pc);
        }
        if (consumers.isEmpty()) return new Result(converted, bails);

        TreeSet<String> listenerPackages = new TreeSet<>();
        for (ParsedConsumer pc : consumers.values()) listenerPackages.add(pc.packageName);
        String basePackage = HybridScaffoldingGenerator.commonDotPrefix(listenerPackages);
        String cdiLookupFqn = basePackage.isBlank() ? "CdiLookup" : basePackage + ".CdiLookup";

        for (ParsedConsumer pc : consumers.values()) {
            String emitted = emit(pc, cdiLookupFqn);
            converted.add(MigratedFile.builder()
                    .originalPath(pc.path).newPath(pc.path).content(emitted)
                    .changeType(FileChangeType.MODIFIED).diffSummary(DIFF_SUMMARY).build());
        }
        log.info("[HybridConsumerTransformer] converted {} consumer class(es), bailed {} to the LLM",
                converted.size(), bails.size());
        return new Result(converted, bails);
    }

    // ── parsing / classification ─────────────────────────────────────────────

    private enum Category { A, B, C }

    private static final class ConsumerMethod {
        String methodName;
        String topicConst;
        String subConst;
        String payloadParam;
        List<String> bodyStatements = new ArrayList<>(); // source text, one per statement
        Category category;
        // injected non-pubsub fields referenced (name → type), for CdiLookup bridging
        Map<String, String> bridgedFields = new LinkedHashMap<>();
        boolean hasPublish;
    }

    private static final class ParsedConsumer {
        String path;
        String packageName;
        String className;
        List<String> keptImports = new ArrayList<>();
        List<String> keptFields = new ArrayList<>();   // non-@Inject fields, source text
        List<ConsumerMethod> methods = new ArrayList<>();
        boolean anyC;
        String bailReason;                              // non-null → class-level bail
    }

    private ParsedConsumer parseConsumerClass(String path, String content, TopicSubscriptionBindings bindings) {
        CompilationUnit cu;
        try {
            cu = new JavaParser().parse(content).getResult().orElse(null);
        } catch (Exception ex) {
            log.debug("[HybridConsumerTransformer] parse skipped for '{}' ({})", path, ex.getMessage());
            return null;
        }
        if (cu == null) return null;

        Optional<ClassOrInterfaceDeclaration> clsOpt = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (clsOpt.isEmpty()) return null;
        ClassOrInterfaceDeclaration cls = clsOpt.get();

        // Must have at least one @Schedule method to be a candidate consumer class.
        List<MethodDeclaration> scheduled = cls.getMethods().stream()
                .filter(m -> m.isAnnotationPresent("Schedule")).toList();
        if (scheduled.isEmpty()) return null;

        ParsedConsumer pc = new ParsedConsumer();
        pc.path = path;
        pc.className = cls.getNameAsString();
        pc.packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        // Identify the PubSubService field name + every @Inject field (name → type).
        String pubsubField = null;
        Map<String, String> injectedFields = new LinkedHashMap<>();
        for (FieldDeclaration fd : cls.getFields()) {
            boolean injected = fd.isAnnotationPresent("Inject");
            String type = fd.getElementType().asString();
            for (var v : fd.getVariables()) {
                String name = v.getNameAsString();
                if (injected) {
                    injectedFields.put(name, type);
                    if (PUBSUB_SERVICE_TYPE.equals(simpleType(type))) pubsubField = name;
                }
            }
            if (!injected) {
                pc.keptFields.add(fd.toString());      // logger, UNIT_PRICE, etc. — preserved verbatim
            }
        }
        if (pubsubField == null) {
            // No PubSubService seam — not the consumer shape this transform handles.
            return null;
        }

        // Imports to keep: drop EJB/Inject/PubSubService; keep the rest (PubSubConfig, domain types, slf4j).
        for (var imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.startsWith("jakarta.ejb.")) continue;
            if (name.equals("jakarta.inject.Inject")) continue;
            if (name.endsWith("." + PUBSUB_SERVICE_TYPE)) continue;
            pc.keptImports.add(imp.toString().trim());
        }

        for (MethodDeclaration m : scheduled) {
            ConsumerMethod cm = classifyMethod(m, pubsubField, injectedFields, bindings, pc);
            if (cm == null) {
                // Any @Schedule method that doesn't match → whole class bails (can't half-convert).
                pc.bailReason = pc.bailReason != null ? pc.bailReason
                        : "method '" + m.getNameAsString() + "' did not match the recognized "
                        + "single-`pubsub.consume(subscription(<SUB>), <n>, <lambda>)` shape, or its "
                        + "subscription has no TopicBootstrap topic binding";
                return pc;
            }
            if (cm.category == Category.C) pc.anyC = true;
            pc.methods.add(cm);
        }
        return pc;
    }

    /** Returns the classified method, or {@code null} when it fails the recognized shape / has no binding. */
    private ConsumerMethod classifyMethod(MethodDeclaration m, String pubsubField,
                                          Map<String, String> injectedFields,
                                          TopicSubscriptionBindings bindings, ParsedConsumer pc) {
        // (1) body is exactly one statement, a call to <pubsubField>.consume(...)
        Optional<BlockStmt> bodyOpt = m.getBody();
        if (bodyOpt.isEmpty()) return null;
        List<Statement> stmts = bodyOpt.get().getStatements();
        if (stmts.size() != 1 || !(stmts.get(0) instanceof ExpressionStmt es)
                || !(es.getExpression() instanceof MethodCallExpr consume)) {
            return null;
        }
        if (!"consume".equals(consume.getNameAsString())
                || consume.getScope().map(s -> !pubsubField.equals(s.toString())).orElse(true)) {
            return null;
        }
        // (2) exactly 3 args: subExpr, int literal, lambda
        if (consume.getArguments().size() != 3) return null;
        Expression subArg = consume.getArgument(0);
        if (!consume.getArgument(1).isIntegerLiteralExpr()) return null;
        if (!(consume.getArgument(2) instanceof LambdaExpr lambda)) return null;

        // (3) arg0 is PubSubConfig.subscription(PubSubConfig.<CONST>) — extract <CONST>
        String subConst = subscriptionConstant(subArg);
        if (subConst == null) return null;

        // (4) <CONST> resolves to a topic via the bindings — never guess
        Optional<String> topic = bindings.topicFor(subConst);
        if (topic.isEmpty()) {
            log.debug("[HybridConsumerTransformer] classifyMethod bail: no TopicBootstrap binding for "
                    + "subscription '{}' in '{}.{}'", subConst, pc.className, m.getNameAsString());
            return null;
        }

        // (5) single-parameter lambda (block or expression)
        if (lambda.getParameters().size() != 1) return null;

        ConsumerMethod cm = new ConsumerMethod();
        cm.methodName = m.getNameAsString();
        cm.subConst = subConst;
        cm.topicConst = topic.get();
        cm.payloadParam = lambda.getParameter(0).getNameAsString();

        // body statements (normalize block vs expression lambda — an expression
        // lambda `payload -> log.info(...)` has an ExpressionStmt body).
        List<Statement> bodyStmts = new ArrayList<>();
        Statement lambdaBody = lambda.getBody();
        if (lambdaBody instanceof BlockStmt block) {
            bodyStmts.addAll(block.getStatements());
        } else if (lambdaBody instanceof ExpressionStmt) {
            bodyStmts.add(lambdaBody);
        } else {
            log.debug("[HybridConsumerTransformer] classifyMethod bail: lambda body type '{}' in '{}.{}' — "
                    + "expected BlockStmt or ExpressionStmt",
                    lambdaBody.getClass().getSimpleName(), pc.className, m.getNameAsString());
            return null;
        }

        // Classify by what the body touches.  Body statements are NOT yet converted to strings
        // here so we can mutate the AST nodes for publish-call rewriting below.
        Set<String> bridged = new LinkedHashSet<>();
        boolean hasPublish = false;
        boolean nonMethodCallFieldUse = false;
        for (Statement s : bodyStmts) {
            for (MethodCallExpr mc : s.findAll(MethodCallExpr.class)) {
                if (mc.getScope().isPresent() && pubsubField.equals(mc.getScope().get().toString())
                        && "publish".equals(mc.getNameAsString())) {
                    hasPublish = true;
                }
            }
            for (NameExpr ne : s.findAll(NameExpr.class)) {
                String n = ne.getNameAsString();
                if (injectedFields.containsKey(n) && !n.equals(pubsubField)) {
                    // is it used as <field>.method(...) (a method-call scope) or otherwise?
                    boolean asCallScope = ne.getParentNode()
                            .filter(p -> p instanceof MethodCallExpr mce
                                    && mce.getScope().map(sc -> sc == ne).orElse(false))
                            .isPresent();
                    if (asCallScope) {
                        bridged.add(n);
                    } else {
                        nonMethodCallFieldUse = true;
                        bridged.add(n);
                    }
                }
            }
        }

        // Rewrite pubsub.publish(PubSubConfig.topic(PubSubConfig.CONST), msg)
        // → kafkaTemplate.send(PubSubConfig.CONST, msg) in the AST before converting to strings.
        // Category stays C (kafkaTemplate field still emitted); the compile gap is eliminated.
        if (hasPublish) {
            for (Statement s : bodyStmts) {
                for (MethodCallExpr mc : new ArrayList<>(s.findAll(MethodCallExpr.class))) {
                    if (!mc.getScope().map(scope -> pubsubField.equals(scope.toString())).orElse(false)) continue;
                    if (!"publish".equals(mc.getNameAsString())) continue;
                    if (mc.getArguments().size() < 2) continue;
                    String topicConst = TopicSubscriptionBindings.constantName(mc.getArgument(0));
                    if (topicConst == null) continue;
                    MethodCallExpr send = new MethodCallExpr();
                    send.setScope(new NameExpr("kafkaTemplate"));
                    send.setName("send");
                    send.addArgument(StaticJavaParser.parseExpression("PubSubConfig." + topicConst));
                    send.addArgument(mc.getArgument(1).clone());
                    mc.replace(send);
                }
            }
        }

        for (String f : bridged) cm.bridgedFields.put(f, injectedFields.get(f));
        cm.hasPublish = hasPublish;
        for (Statement s : bodyStmts) {
            cm.bodyStatements.add(s.toString());
        }

        if (hasPublish || bridged.size() >= 2 || nonMethodCallFieldUse) {
            cm.category = Category.C;
        } else if (bridged.size() == 1) {
            cm.category = Category.B;
        } else {
            cm.category = Category.A;
        }
        return cm;
    }

    /** Extract {@code <CONST>} from {@code PubSubConfig.subscription(PubSubConfig.<CONST>)}. */
    private static String subscriptionConstant(Expression subArg) {
        if (!(subArg instanceof MethodCallExpr mce)) return null;
        if (!"subscription".equals(mce.getNameAsString())) return null;
        if (mce.getScope().map(s -> !CONFIG_TYPE.equals(s.toString())).orElse(true)) return null;
        if (mce.getArguments().size() != 1) return null;
        return TopicSubscriptionBindings.constantName(mce.getArgument(0));
    }

    // ── emission ─────────────────────────────────────────────────────────────

    private String emit(ParsedConsumer pc, String cdiLookupFqn) {
        Set<String> imports = new LinkedHashSet<>(pc.keptImports);
        imports.add("import org.springframework.kafka.annotation.KafkaListener;");
        imports.add("import org.springframework.stereotype.Component;");
        boolean anyBridged = pc.methods.stream().anyMatch(m -> !m.bridgedFields.isEmpty());
        if (anyBridged) imports.add("import " + cdiLookupFqn + ";");
        if (pc.anyC) {
            imports.add("import org.springframework.beans.factory.annotation.Autowired;");
            imports.add("import org.springframework.kafka.core.KafkaTemplate;");
        }

        StringBuilder sb = new StringBuilder();
        if (!pc.packageName.isBlank()) sb.append("package ").append(pc.packageName).append(";\n\n");
        List<String> sorted = new ArrayList<>(imports);
        sorted.sort(String::compareTo);
        for (String imp : sorted) sb.append(imp).append("\n");
        sb.append("\n");

        sb.append("@Component\n");
        sb.append("public class ").append(pc.className).append(" {\n\n");

        for (String field : pc.keptFields) {
            sb.append("    ").append(field.replace("\n", "\n    ")).append("\n\n");
        }
        if (pc.anyC) {
            sb.append("    @Autowired\n");
            sb.append("    KafkaTemplate<String, String> kafkaTemplate;\n\n");
        }

        for (ConsumerMethod m : pc.methods) {
            sb.append("    @KafkaListener(topics = ").append(CONFIG_TYPE).append(".").append(m.topicConst)
              .append(", groupId = ").append(CONFIG_TYPE).append(".").append(m.subConst).append(")\n");
            sb.append("    public void ").append(m.methodName).append("(String ").append(m.payloadParam)
              .append(") {\n");
            // CdiLookup locals (one per bridged field), declared once at the top
            for (Map.Entry<String, String> bf : m.bridgedFields.entrySet()) {
                sb.append("        ").append(bf.getValue()).append(" ").append(bf.getKey())
                  .append(" = CdiLookup.get(").append(simpleType(bf.getValue())).append(".class);\n");
            }
            for (String stmt : m.bodyStatements) {
                sb.append("        ").append(stmt.replace("\n", "\n        ")).append("\n");
            }
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String simpleType(String type) {
        int lt = type.indexOf('<');
        String raw = lt >= 0 ? type.substring(0, lt) : type;
        int dot = raw.lastIndexOf('.');
        return (dot >= 0 ? raw.substring(dot + 1) : raw).trim();
    }
}
