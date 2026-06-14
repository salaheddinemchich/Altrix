package com.altrix.orchestrator.infrastructure.semantic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic structural repair for the Lombok constructor collision the
 * migrator produces: a class annotated {@code @RequiredArgsConstructor} (or
 * {@code @AllArgsConstructor} / {@code @NoArgsConstructor}) that ALSO declares
 * an explicit constructor with the SAME signature Lombok would generate →
 * {@code "constructor X is already defined"}.
 *
 * <p>Fix: drop the redundant Lombok annotation and keep the explicit
 * constructor (the one the model hand-wrote, which carries the real body).
 * Removal is gated on an EXACT match of the generated constructor's parameter
 * types — not just arity — so a class with an unrelated same-arity
 * constructor is left untouched.
 *
 * <p>Uses {@link LexicalPreservingPrinter} so only the annotation is removed;
 * the rest of the file keeps its original formatting.  Best-effort: a file
 * that fails to parse is returned unchanged.
 */
@Slf4j
@Component
public class LombokConstructorReconciler {

    public Result reconcile(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return new Result(files == null ? Map.of() : files, List.of());
        }
        Map<String, String> out = new LinkedHashMap<>(files.size());
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || !path.toLowerCase().endsWith(".java") || content == null) {
                out.put(path, content);
                continue;
            }
            try {
                String fixed = reconcileOne(content);
                out.put(path, fixed);
                if (!fixed.equals(content)) changed.add(path);
            } catch (Exception ex) {
                log.debug("Lombok reconcile skipped for '{}' ({})", path, ex.getMessage());
                out.put(path, content);
            }
        }
        return new Result(out, changed);
    }

    private String reconcileOne(String content) {
        ParseResult<CompilationUnit> parsed = new JavaParser().parse(content);
        CompilationUnit cu = parsed.getResult().orElse(null);
        if (cu == null) return content;
        LexicalPreservingPrinter.setup(cu);

        boolean[] changed = {false};
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            if (cls.isInterface()) return;
            List<ConstructorDeclaration> ctors = cls.getConstructors();
            if (ctors.isEmpty()) return;

            boolean hasRequired = stripIfMatches(cls, "RequiredArgsConstructor",
                    requiredArgsTypes(cls), ctors, changed);
            // After possibly removing @RequiredArgsConstructor, re-check the others.
            if (!hasRequired) {
                stripIfMatches(cls, "AllArgsConstructor", allArgsTypes(cls), ctors, changed);
                stripIfMatches(cls, "NoArgsConstructor", List.of(), ctors, changed);
            }
        });
        return changed[0] ? LexicalPreservingPrinter.print(cu) : content;
    }

    /**
     * Removes {@code @annoName} when an explicit constructor's parameter types
     * exactly match {@code generatedTypes}.  Returns true when the annotation
     * is still present afterwards (i.e. NOT removed) so the caller knows the
     * Lombok constructor still exists.
     */
    private boolean stripIfMatches(ClassOrInterfaceDeclaration cls, String annoName,
                                   List<String> generatedTypes, List<ConstructorDeclaration> ctors,
                                   boolean[] changed) {
        AnnotationExpr anno = cls.getAnnotationByName(annoName).orElse(null);
        if (anno == null) return false;
        for (ConstructorDeclaration ctor : ctors) {
            if (ctorParamTypes(ctor).equals(generatedTypes)) {
                anno.remove();
                changed[0] = true;
                return false; // annotation removed → Lombok ctor gone
            }
        }
        return true; // annotation kept
    }

    /** Param types Lombok's {@code @RequiredArgsConstructor} would generate: final, uninitialised, non-static fields in order. */
    private static List<String> requiredArgsTypes(ClassOrInterfaceDeclaration cls) {
        List<String> types = new ArrayList<>();
        for (FieldDeclaration f : cls.getFields()) {
            if (f.isStatic()) continue;
            f.getVariables().forEach(v -> {
                if (f.isFinal() && v.getInitializer().isEmpty()) {
                    types.add(simpleType(f.getElementType().toString()));
                }
            });
        }
        return types;
    }

    /** Param types {@code @AllArgsConstructor} would generate: every non-static field in order. */
    private static List<String> allArgsTypes(ClassOrInterfaceDeclaration cls) {
        List<String> types = new ArrayList<>();
        for (FieldDeclaration f : cls.getFields()) {
            if (f.isStatic()) continue;
            f.getVariables().forEach(v -> types.add(simpleType(f.getElementType().toString())));
        }
        return types;
    }

    private static List<String> ctorParamTypes(ConstructorDeclaration ctor) {
        List<String> types = new ArrayList<>();
        ctor.getParameters().forEach(p -> types.add(simpleType(p.getType().toString())));
        return types;
    }

    /** Strip generics + array + package qualifier so comparisons are name-based. */
    private static String simpleType(String t) {
        int lt = t.indexOf('<');
        if (lt > 0) t = t.substring(0, lt);
        int br = t.indexOf('[');
        if (br > 0) t = t.substring(0, br);
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(dot + 1);
        return t.trim();
    }

    public record Result(Map<String, String> reconciledFiles, List<String> changedPaths)
            implements Serializable {
        public Result {
            reconciledFiles = reconciledFiles == null ? Map.of() : Map.copyOf(reconciledFiles);
            changedPaths    = changedPaths    == null ? List.of() : List.copyOf(changedPaths);
        }
        public boolean changedAnything() { return !changedPaths.isEmpty(); }
    }
}
