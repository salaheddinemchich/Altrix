package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.stmt.BlockStmt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministically rewrites a Pub/Sub configuration <em>constants</em> class
 * (the {@code PubSubConfig} shape) down to a pure constants holder for the
 * {@code SPRING_KAFKA_HYBRID} target — the same role the hand-written
 * {@code KafkaTopics} class plays in the reference project.
 *
 * <p><b>Why deterministic, not LLM.</b> Left to a free-tier model this single
 * file was the dominant failure source: it grew duplicate {@code @Configuration}
 * / {@code @Bean} boilerplate (a second, conflicting Kafka config beside the
 * generated {@code SpringKafkaConfig}), renamed {@code *_SUB} constants to
 * {@code *_GROUP} (silently breaking the {@code @KafkaListener(groupId = ...)}
 * references the {@link HybridConsumerTransformer} already emitted against the
 * original names), and dual-imported {@code KafkaTemplate}/{@code ProducerFactory}
 * from both the wrong and right packages. Anchoring it to constants-only removes
 * every one of those degrees of freedom: the constant <em>names and values</em>
 * are preserved byte-for-byte; everything else is stripped.
 *
 * <p><b>Detection is by SHAPE, never by class name.</b> Handles three forms the
 * class can arrive in across retry jobs:
 * <ul>
 *   <li><b>Original source</b> — {@code private} no-argument constructor (utility
 *       class), at least one {@code static final String} literal constant, and
 *       {@code topic()} / {@code subscription()} GCP helper methods.</li>
 *   <li><b>LLM-migrated</b> — the LLM dropped the private ctor and added
 *       {@code @Configuration}/{@code @Bean} boilerplate. Detected by
 *       {@code @Configuration} annotation + at least one literal constant.</li>
 *   <li><b>Previously-anchored</b> — a prior run already produced constants-only
 *       output. Detected by private no-arg ctor + at least one literal constant
 *       (the rewrite always adds a private ctor when none exists, so this form
 *       is stable across jobs).</li>
 * </ul>
 * <p>The rewrite is idempotent for the already-pure form (nothing to strip →
 * same output). Anchoring it anyway prevents the LLM from re-adding
 * {@code @Configuration}/{@code @Bean} on the next retry.
 *
 * <p><b>The rewrite</b> keeps only the literal-string constants and the private
 * constructor; it removes all imports, all class annotations, all methods, all
 * non-literal fields, and all comments. The result is verified to re-parse before
 * being returned. Returns {@code null} when no class matches.
 */
@Slf4j
@Component
public class PubSubConfigAnchor {

    private static final String DIFF_SUMMARY =
            "Anchored: constants-only rewrite, Spring/@Bean additions and GCP helper methods removed, "
                    + "prevents LLM from hallucinating duplicate configuration.";

    /**
     * Find the Pub/Sub constants class in {@code sourceFiles} and return its
     * constants-only rewrite as a {@link MigratedFile}, or {@code null} when none
     * is recognized (or it is already pure).
     */
    public MigratedFile anchor(Map<String, String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return null;

        for (Map.Entry<String, String> e : sourceFiles.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !path.toLowerCase().endsWith(".java")) continue;

            CompilationUnit cu;
            try {
                cu = new JavaParser().parse(content).getResult().orElse(null);
            } catch (Exception ex) {
                log.debug("[PubSubConfigAnchor] parse skipped for '{}' ({})", path, ex.getMessage());
                continue;
            }
            if (cu == null) continue;

            Optional<ClassOrInterfaceDeclaration> clsOpt = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    this::isConstantsHolderToAnchor);
            if (clsOpt.isEmpty()) continue;
            ClassOrInterfaceDeclaration cls = clsOpt.get();

            rewriteToConstantsOnly(cu, cls);

            String rewritten = cu.toString();
            // Defensive: never emit something that doesn't parse.
            try {
                if (new JavaParser().parse(rewritten).getResult().isEmpty()) {
                    log.warn("[PubSubConfigAnchor] rewrite of '{}' did not re-parse — leaving original for the LLM", path);
                    return null;
                }
            } catch (Exception ex) {
                log.warn("[PubSubConfigAnchor] rewrite of '{}' did not re-parse ({}) — leaving original", path, ex.getMessage());
                return null;
            }

            log.info("[PubSubConfigAnchor] anchored constants class '{}' to constants-only form", path);
            return MigratedFile.builder()
                    .originalPath(path).newPath(path).content(rewritten)
                    .changeType(FileChangeType.MODIFIED).diffSummary(DIFF_SUMMARY).build();
        }
        return null;
    }

    /** Shape detector — see class javadoc. */
    private boolean isConstantsHolderToAnchor(ClassOrInterfaceDeclaration cls) {
        boolean hasPrivateNoArgCtor = cls.getConstructors().stream()
                .anyMatch(c -> c.isPrivate() && c.getParameters().isEmpty());
        // LLM-migrated form: lost the private ctor but gained @Configuration.
        boolean hasConfigurationAnnotation = cls.getAnnotations().stream()
                .anyMatch(a -> "Configuration".equals(a.getNameAsString()));

        if (!hasPrivateNoArgCtor && !hasConfigurationAnnotation) return false;

        // Must preserve at least one literal-string constant.
        // hasSomethingToStrip is intentionally absent: a pure constants-only class
        // (already anchored, nothing to strip) is still anchored here to prevent
        // the LLM from re-adding @Configuration/@Bean on the next retry job.
        return cls.getFields().stream().anyMatch(this::isStaticFinalStringLiteral);
    }

    private void rewriteToConstantsOnly(CompilationUnit cu, ClassOrInterfaceDeclaration cls) {
        cu.getImports().clear();
        cls.getAnnotations().clear();

        // Remove everything except the private no-arg constructor and the
        // literal-string constants. Collect first to avoid concurrent mutation.
        List<BodyDeclaration<?>> toRemove = new ArrayList<>();
        for (BodyDeclaration<?> member : cls.getMembers()) {
            if (member instanceof ConstructorDeclaration ctor) {
                if (!(ctor.isPrivate() && ctor.getParameters().isEmpty())) toRemove.add(member);
            } else if (member instanceof FieldDeclaration fd) {
                if (!isStaticFinalStringLiteral(fd)) toRemove.add(member);
            } else if (member instanceof MethodDeclaration) {
                toRemove.add(member);
            } else {
                toRemove.add(member); // nested types, initializers, etc.
            }
        }
        toRemove.forEach(cls::remove);

        // Guarantee a private no-arg ctor exists in the output so the next retry
        // job's anchor can re-detect the already-pure form via hasPrivateNoArgCtor.
        // The LLM-migrated @Configuration form has no private ctor; without this
        // the re-stamped output would have neither hasPrivateNoArgCtor nor
        // @Configuration and would slip through to the LLM on the next run.
        boolean stillHasPrivateCtor = cls.getConstructors().stream()
                .anyMatch(c -> c.isPrivate() && c.getParameters().isEmpty());
        if (!stillHasPrivateCtor) {
            ConstructorDeclaration added = new ConstructorDeclaration();
            added.setName(cls.getNameAsString());
            added.addModifier(Modifier.Keyword.PRIVATE);
            added.setBody(new BlockStmt());
            cls.addMember(added);
        }

        // Strip all comments (class/field javadocs, the GCP "projects/..." notes).
        List<Comment> comments = new ArrayList<>(cu.getAllContainedComments());
        cu.getComment().ifPresent(comments::add);
        comments.forEach(Comment::remove);
    }

    /** True for {@code static final String FOO = "literal";} (all variables literal). */
    private boolean isStaticFinalStringLiteral(FieldDeclaration fd) {
        if (!fd.isStatic() || !fd.isFinal()) return false;
        if (!"String".equals(fd.getElementType().asString())) return false;
        for (VariableDeclarator v : fd.getVariables()) {
            if (v.getInitializer().filter(com.github.javaparser.ast.expr.Expression::isStringLiteralExpr).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
