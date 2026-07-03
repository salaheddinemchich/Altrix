package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid-only (Jakarta EE + Spring Kafka), detect-only defense-in-depth for
 * the transaction gap {@code CdiStatelessConverter} auto-fixes.
 *
 * <p><b>Why detect-only, not auto-fix here too.</b> {@code CdiStatelessConverter}
 * already runs inside {@code CoreMigratorAgent} and resolves every statically
 * determinable {@code CdiLookup.get(X.class)} target — by the time this
 * validator sees the artifact, the common case is already converted. This
 * class exists for what the converter's static scan structurally cannot
 * resolve: a {@code CdiLookup.get(...)} call whose argument is not a literal
 * {@code X.class} (a variable, a method call, anything computed) — there is
 * no safe mechanical fix for "find the class this expression evaluates to,"
 * so it is surfaced for a human / the AI repair tier instead, same rationale
 * as {@code SpringKafkaOverEngineeringDetector}.
 *
 * <p>Each file is JavaParser-parsed at most once per {@link #detect} call —
 * a cheap substring pre-filter ({@code @Stateless}/{@code @ApplicationScoped}
 * for scope collection, {@code @KafkaListener}+{@code CdiLookup} for call-site
 * detection) decides whether a file needs parsing at all, and a file matching
 * both filters reuses the same parsed {@link CompilationUnit} rather than
 * being parsed twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringKafkaTxGapDetector {

    private final CdiLookupCallScanner cdiLookupCallScanner;

    public List<Detection> detect(Map<String, String> javaFiles) {
        List<Detection> out = new ArrayList<>();
        if (javaFiles == null || javaFiles.isEmpty()) return out;

        Map<String, String> scopeBySimpleName = new HashMap<>();
        Map<String, CompilationUnit> listenerUnits = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;

            boolean scopeRelevant = content.contains("@Stateless") || content.contains("@ApplicationScoped");
            boolean listenerRelevant = content.contains("@KafkaListener") && content.contains("CdiLookup");
            if (!scopeRelevant && !listenerRelevant) continue;

            CompilationUnit cu = parseOrNull(content, path);
            if (cu == null) continue;

            if (scopeRelevant) collectScopes(cu, scopeBySimpleName);
            if (listenerRelevant) listenerUnits.put(path, cu);
        }

        for (Map.Entry<String, CompilationUnit> e : listenerUnits.entrySet()) {
            try {
                out.addAll(detectOne(e.getKey(), e.getValue(), scopeBySimpleName));
            } catch (Exception ex) {
                log.debug("Spring-Kafka tx-gap detection skipped for '{}' ({})", e.getKey(), ex.getMessage());
            }
        }
        return out;
    }

    private List<Detection> detectOne(String path, CompilationUnit cu, Map<String, String> scopeBySimpleName) {
        List<Detection> out = new ArrayList<>();
        boolean hasListener = cu.findFirst(AnnotationExpr.class,
                a -> "KafkaListener".equals(a.getNameAsString())).isPresent();
        if (!hasListener) return out;

        for (CdiLookupCallScanner.CdiLookupCall call : cdiLookupCallScanner.scan(cu)) {
            if (call.literalTargetSimpleName() == null) {
                out.add(new Detection(path, call.line(), call.argument().toString(),
                        "CdiLookup.get(" + call.argument() + ") target is not a literal 'X.class' — cannot "
                                + "statically verify it is @Stateless. Manually confirm the resolved class is "
                                + "@Stateless with @TransactionAttribute(REQUIRES_NEW) on methods called from this "
                                + "listener; a Kafka consumer thread carries no JTA transaction otherwise."));
                continue;
            }

            String simple = call.literalTargetSimpleName();
            String scope = scopeBySimpleName.get(simple);
            if ("ApplicationScoped".equals(scope)) {
                out.add(new Detection(path, call.line(), simple,
                        "CdiLookup.get(" + simple + ".class) target is still @ApplicationScoped. A Kafka consumer "
                                + "thread carries no JTA transaction; convert '" + simple + "' to @Stateless "
                                + "(jakarta.ejb.Stateless) with @TransactionAttribute(REQUIRES_NEW) on the methods "
                                + "called from listener threads."));
            }
        }
        return out;
    }

    /** Simple class name → "ApplicationScoped" or "Stateless", whichever scope annotation it carries. */
    private void collectScopes(CompilationUnit cu, Map<String, String> scopes) {
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isAnnotationPresent("Stateless")) {
                scopes.put(cls.getNameAsString(), "Stateless");
            } else if (cls.isAnnotationPresent("ApplicationScoped")) {
                scopes.put(cls.getNameAsString(), "ApplicationScoped");
            }
        }
    }

    private CompilationUnit parseOrNull(String content, String path) {
        try {
            return new JavaParser().parse(content).getResult().orElse(null);
        } catch (Exception e) {
            log.debug("Scope/listener scan skipped for '{}' ({})", path, e.getMessage());
            return null;
        }
    }

    /** One transaction-gap smell, located precisely for the AI repair tier / human review. */
    public record Detection(String filePath, int line, String symbol, String message)
            implements Serializable {
        public Detection {
            if (symbol == null) symbol = "";
            if (message == null) message = "";
        }
    }
}
