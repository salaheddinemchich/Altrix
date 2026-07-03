package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Hybrid-only (Jakarta EE + Spring Kafka), deterministic auto-fix: converts a
 * CDI persistence/business class to a {@code @Stateless} EJB when it is
 * reached from a Spring {@code @KafkaListener} via {@code CdiLookup.get(...)}.
 *
 * <p><b>Why this exists.</b> A Kafka consumer thread carries no JTA
 * transaction. Only an EJB proxy boundary creates one regardless of which
 * thread calls it — leaving the class {@code @ApplicationScoped} is a real
 * transaction gap, not a style nit. See {@code SpringKafkaTxGapDetector} for
 * the defense-in-depth, detect-only counterpart that flags whatever this
 * static scan can't resolve (e.g. a dynamically-computed
 * {@code CdiLookup.get(...)} argument).
 *
 * <p><b>Class resolution is simple-name based</b> — the reached class's
 * declared simple name is matched against the {@code Foo.class} argument of
 * each {@code CdiLookup.get(Foo.class)} call ({@link CdiLookupCallScanner},
 * shared with {@code SpringKafkaTxGapDetector}). Same limitation already
 * accepted for the existing Project Semantic Index ({@code ContractValidator})
 * — acceptable here for the same reason: ambiguous cross-package name
 * collisions are rare and the detect-only validator catches what this misses.
 *
 * <p><b>Mutation is JavaParser AST-based</b> ({@code LexicalPreservingPrinter}
 * preserves untouched formatting/comments), NOT regex over raw text — an
 * earlier regex implementation silently failed to convert a class whose
 * {@code @ApplicationScoped} had another annotation between it and {@code
 * class} (e.g. {@code @Named}), and separately injected the transaction
 * annotation into commented-out method signatures. Both failure modes are
 * structurally impossible against the AST: annotation lookup is by name
 * regardless of position, and comments are never method declarations.
 *
 * <p>Idempotent: a class with no {@code @ApplicationScoped} annotation (already
 * {@code @Stateless}, or scoped some other way) is left untouched, and {@link
 * #convert} only ever reports a file as MODIFIED when it actually converted at
 * least one class — it never claims success it didn't achieve.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdiStatelessConverter {

    private static final String NOTE = "Converted to @Stateless EJB — required for transaction "
            + "correctness when invoked from Spring Kafka consumer threads.";

    private final CdiLookupCallScanner cdiLookupCallScanner;

    public List<MigratedFile> convert(List<MigratedFile> files) {
        if (files == null || files.isEmpty()) return files;

        Set<String> reachedSimpleNames = collectCdiLookupTargets(files);
        if (reachedSimpleNames.isEmpty()) return files;

        List<MigratedFile> out = new ArrayList<>(files.size());
        for (MigratedFile f : files) {
            String content = f.content();
            // Cheap pre-filter before parsing — mirrors the "content.contains(...)"
            // gate this codebase already uses elsewhere (e.g. the CdiLookup
            // substring check below) to avoid JavaParser-parsing files that
            // can't possibly need conversion.
            if (content == null || !content.contains("@ApplicationScoped")) {
                out.add(f);
                continue;
            }
            MigratedFile converted = tryConvert(f, content, reachedSimpleNames);
            out.add(converted != null ? converted : f);
        }
        return out;
    }

    /** Simple names of every literal {@code CdiLookup.get(X.class)} argument across all files. */
    private Set<String> collectCdiLookupTargets(List<MigratedFile> files) {
        Set<String> targets = new LinkedHashSet<>();
        for (MigratedFile f : files) {
            String content = f.content();
            if (content == null || !content.contains("CdiLookup")) continue;
            CompilationUnit cu = parseOrNull(content, f.originalPath());
            if (cu == null) continue;
            for (CdiLookupCallScanner.CdiLookupCall call : cdiLookupCallScanner.scan(cu)) {
                if (call.literalTargetSimpleName() != null) {
                    targets.add(call.literalTargetSimpleName());
                }
            }
        }
        return targets;
    }

    /**
     * Returns the converted file, or {@code null} when no reached class in
     * this file actually had an {@code @ApplicationScoped} annotation to
     * convert. The caller must treat {@code null} as "leave unchanged" —
     * NEVER report MODIFIED with the EJB-conversion note unless a real
     * conversion happened (the bug this class was rewritten to fix: the old
     * regex implementation reported success unconditionally once a file was
     * merely a candidate, regardless of whether the rewrite actually took).
     */
    private MigratedFile tryConvert(MigratedFile f, String content, Set<String> reachedSimpleNames) {
        CompilationUnit cu = parseOrNull(content, f.originalPath());
        if (cu == null) return null;

        LexicalPreservingPrinter.setup(cu);

        boolean changed = false;
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!reachedSimpleNames.contains(cls.getNameAsString())) continue;
            Optional<AnnotationExpr> scoped = cls.getAnnotationByName("ApplicationScoped");
            if (scoped.isEmpty()) continue;

            scoped.get().replace(new MarkerAnnotationExpr("Stateless"));
            for (MethodDeclaration method : cls.getMethods()) {
                if (method.isPublic() && !method.isAnnotationPresent("TransactionAttribute")) {
                    method.getAnnotations().add(transactionAttributeRequiresNew());
                }
            }
            changed = true;
        }
        if (!changed) return null;

        addImportIfMissing(cu, "jakarta.ejb.Stateless");
        addImportIfMissing(cu, "jakarta.ejb.TransactionAttribute");
        addImportIfMissing(cu, "jakarta.ejb.TransactionAttributeType");
        removeImportIfNoLongerUsed(cu, "jakarta.enterprise.context.ApplicationScoped", "ApplicationScoped");

        String converted = LexicalPreservingPrinter.print(cu);
        return new MigratedFile(f.originalPath(), f.newPath(), converted, FileChangeType.MODIFIED, NOTE);
    }

    private static SingleMemberAnnotationExpr transactionAttributeRequiresNew() {
        return new SingleMemberAnnotationExpr(new Name("TransactionAttribute"),
                new FieldAccessExpr(new NameExpr("TransactionAttributeType"), "REQUIRES_NEW"));
    }

    private static void addImportIfMissing(CompilationUnit cu, String fqn) {
        boolean present = cu.getImports().stream().anyMatch(i -> fqn.equals(i.getNameAsString()));
        if (!present) cu.addImport(fqn);
    }

    /** Drops the import only when no annotation in the whole file still uses it. */
    private static void removeImportIfNoLongerUsed(CompilationUnit cu, String fqn, String annotationSimpleName) {
        boolean stillUsed = cu.findAll(AnnotationExpr.class).stream()
                .anyMatch(a -> annotationSimpleName.equals(a.getNameAsString()));
        if (stillUsed) return;
        cu.getImports().removeIf(i -> fqn.equals(i.getNameAsString()));
    }

    private CompilationUnit parseOrNull(String content, String path) {
        try {
            return new JavaParser().parse(content).getResult().orElse(null);
        } catch (Exception e) {
            log.debug("CdiStatelessConverter parse skipped for '{}' ({})", path, e.getMessage());
            return null;
        }
    }
}
