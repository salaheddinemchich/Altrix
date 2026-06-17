package com.altrix.orchestrator.infrastructure.semantic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fixes a runtime NPE the migrator introduces that COMPILES but crashes at
 * startup: a {@code @Value}-annotated FIELD that is read while the bean is being
 * constructed.  Spring injects {@code @Value} fields AFTER the constructor runs,
 * so the field is {@code null} during construction → {@code NullPointerException}
 * on boot.
 *
 * <p>Two shapes are handled:
 *
 * <pre>{@code
 * // (1) direct read in the constructor body
 * @Value("${spring.kafka.bootstrap-servers}") private String servers;
 * public Pub() { props.put("bootstrap.servers", servers); }       // servers == null!
 *
 * // (2) CONSTRUCTION-PATH read: the field is read in a helper method that is
 * //     invoked (transitively) from the constructor — the exact bd4e0ba5 bug.
 * @Value("${spring.kafka.bootstrap-servers}") private String servers;
 * public Pub() { this.producer = new KafkaProducer<>(producerProps()); }
 * private Properties producerProps() { p.put("bootstrap.servers", servers); } // null!
 * }</pre>
 *
 * The fix is the canonical one: inject the value as a <b>constructor parameter</b>,
 * where it is present before any construction logic (or helper) runs.
 *
 * <ul>
 *   <li><b>Move</b> — when the field is read ONLY directly in the constructor and
 *       nowhere else (and never via {@code this.field}): drop the field entirely
 *       and add a {@code @Value} parameter.  The bare references in the body then
 *       resolve to the parameter.</li>
 *   <li><b>Keep-and-assign</b> — when the field is also read outside the
 *       constructor (e.g. a helper method): KEEP the field (now {@code final}),
 *       strip its {@code @Value}, add a {@code @Value} constructor parameter, and
 *       assign {@code this.field = field} as the first statement so every helper
 *       sees a non-null value.</li>
 * </ul>
 *
 * <p>Conservative guards keep both paths safe: a single constructor only; the
 * field must actually be read during construction (no churn on correct field
 * injection); for keep-and-assign the field must have no initializer and must not
 * be assigned anywhere else (so {@code final} is valid).  Uses
 * {@link LexicalPreservingPrinter} to preserve formatting.
 */
@Slf4j
@Component
public class SpringValueConstructorInjectionFixer {

    public Result fix(Map<String, String> files) {
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
                String fixed = fixOne(content);
                out.put(path, fixed);
                if (!fixed.equals(content)) changed.add(path);
            } catch (Exception ex) {
                log.debug("Spring @Value fix skipped for '{}' ({})", path, ex.getMessage());
                out.put(path, content);
            }
        }
        return new Result(out, changed);
    }

    private String fixOne(String content) {
        if (!content.contains("@Value")) return content;
        CompilationUnit cu = new JavaParser().parse(content).getResult().orElse(null);
        if (cu == null) return content;
        LexicalPreservingPrinter.setup(cu);

        boolean[] changed = {false};
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            List<ConstructorDeclaration> ctors = cls.getConstructors();
            if (ctors.size() != 1) return;                 // single-constructor only
            ConstructorDeclaration ctor = ctors.get(0);

            // Methods reachable (transitively) from the constructor — a field read
            // in any of them happens DURING construction, hence null.
            Set<String> ctorReachableMethods = transitiveCallsFrom(ctor, cls);

            for (FieldDeclaration fd : new ArrayList<>(cls.getFields())) {
                if (fd.getVariables().size() != 1) continue;
                AnnotationExpr value = fd.getAnnotationByName("Value").orElse(null);
                if (value == null) continue;
                String name = fd.getVariable(0).getNameAsString();

                boolean directlyReadInCtor = readsName(ctor, name) || readsThisField(ctor, name);
                boolean readViaHelper = ctorReachableMethods.stream()
                        .flatMap(m -> cls.getMethodsByName(m).stream())
                        .anyMatch(md -> readsName(md, name) || readsThisField(md, name));
                boolean readDuringConstruction = directlyReadInCtor || readViaHelper;
                if (!readDuringConstruction) continue;     // correct field injection — leave it

                // ── Path A: MOVE (remove field) — read ONLY directly in the ctor.
                boolean usedOutsideCtor = cu.findAll(NameExpr.class).stream()
                        .filter(n -> n.getNameAsString().equals(name))
                        .anyMatch(n -> n.findAncestor(ConstructorDeclaration.class).isEmpty());
                boolean thisAccessAnywhere = cu.findAll(FieldAccessExpr.class).stream()
                        .anyMatch(fa -> fa.getNameAsString().equals(name) && fa.getScope() instanceof ThisExpr);
                if (readsName(ctor, name) && !usedOutsideCtor && !thisAccessAnywhere) {
                    Parameter param = new Parameter(fd.getElementType(), name);
                    param.addAnnotation(value.clone());
                    ctor.addParameter(param);
                    fd.remove();
                    changed[0] = true;
                    continue;
                }

                // ── Path B: KEEP-AND-ASSIGN — field also read elsewhere (helper).
                // Safe only when the field has no initializer and is not assigned
                // anywhere (so we can make it final and assign once in the ctor).
                if (fd.getVariable(0).getInitializer().isPresent()) continue;
                if (assignedAnywhere(cu, name)) continue;
                if (ctor.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) continue;

                fd.getAnnotations().remove(value);
                fd.setFinal(true);
                Parameter param = new Parameter(fd.getElementType(), name);
                param.addAnnotation(value.clone());
                ctor.addParameter(param);
                Expression assign = new AssignExpr(
                        new FieldAccessExpr(new ThisExpr(), name),
                        new NameExpr(name),
                        AssignExpr.Operator.ASSIGN);
                ctor.getBody().addStatement(0, new ExpressionStmt(assign));
                changed[0] = true;
            }
        });
        return changed[0] ? LexicalPreservingPrinter.print(cu) : content;
    }

    /**
     * BFS over the call graph starting at the constructor, following only calls
     * to this class's own instance methods (unqualified or {@code this.}-scoped).
     * Returns the set of reachable method names.
     */
    private Set<String> transitiveCallsFrom(ConstructorDeclaration ctor, ClassOrInterfaceDeclaration cls) {
        Set<String> own = cls.getMethods().stream()
                .map(MethodDeclaration::getNameAsString).collect(Collectors.toSet());
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        ctor.findAll(MethodCallExpr.class).forEach(c -> {
            if (isOwnCall(c, own)) queue.add(c.getNameAsString());
        });
        while (!queue.isEmpty()) {
            String m = queue.poll();
            if (!visited.add(m)) continue;
            cls.getMethodsByName(m).forEach(md ->
                    md.findAll(MethodCallExpr.class).forEach(c -> {
                        if (isOwnCall(c, own)) queue.add(c.getNameAsString());
                    }));
        }
        return visited;
    }

    private boolean isOwnCall(MethodCallExpr call, Set<String> ownMethods) {
        boolean selfScoped = call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr;
        return selfScoped && ownMethods.contains(call.getNameAsString());
    }

    /** True when {@code node} reads the bare name and does not shadow it with a parameter. */
    private boolean readsName(com.github.javaparser.ast.Node node, String name) {
        if (node instanceof MethodDeclaration md
                && md.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) {
            return false; // parameter shadows the field inside this method
        }
        return node.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(name));
    }

    private boolean readsThisField(com.github.javaparser.ast.Node node, String name) {
        return node.findAll(FieldAccessExpr.class).stream()
                .anyMatch(fa -> fa.getNameAsString().equals(name) && fa.getScope() instanceof ThisExpr);
    }

    /** True when the field is the target of any assignment (bare or {@code this.}). */
    private boolean assignedAnywhere(CompilationUnit cu, String name) {
        return cu.findAll(AssignExpr.class).stream().anyMatch(a -> {
            Expression t = a.getTarget();
            if (t instanceof NameExpr ne) return ne.getNameAsString().equals(name);
            if (t instanceof FieldAccessExpr fa) {
                return fa.getNameAsString().equals(name) && fa.getScope() instanceof ThisExpr;
            }
            return false;
        });
    }

    public record Result(Map<String, String> fixedFiles, List<String> changedPaths)
            implements Serializable {
        public Result {
            fixedFiles   = fixedFiles   == null ? Map.of() : Map.copyOf(fixedFiles);
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
        public boolean changedAnything() { return !changedPaths.isEmpty(); }
    }
}
