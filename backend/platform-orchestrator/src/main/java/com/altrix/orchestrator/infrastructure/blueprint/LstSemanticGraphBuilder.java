package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.*;
import lombok.extern.slf4j.Slf4j;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks OpenRewrite LSTs and assembles a type-attributed
 * {@link SemanticGraph} — the project-wide structural map the migrator
 * reads to understand "what is this class, what does it implement, who
 * calls it, what does it import".
 *
 * <p>Project-owned types are the set of FQNs declared by the parsed
 * sources; any referenced type outside that set is recorded as
 * external ({@code isProjectOwned=false}) so the migrator can tell a
 * project call from a library call.
 *
 * <p>Best-effort + null-tolerant: OpenRewrite leaves type attribution
 * partial when external JARs aren't on the parser classpath, so every
 * type read is guarded.  A node we can't attribute is simply omitted
 * rather than failing the whole build.
 */
@Slf4j
@Component
public class LstSemanticGraphBuilder {

    public SemanticGraph build(List<J.CompilationUnit> units) {
        if (units == null || units.isEmpty()) return SemanticGraph.empty();

        // First pass — collect every project-declared FQN so call/inheritance
        // edges can be classified project vs external.
        Set<String> projectFqns = new HashSet<>();
        for (J.CompilationUnit cu : units) {
            for (J.ClassDeclaration cd : cu.getClasses()) {
                collectDeclaredFqns(cd, projectFqns);
            }
        }

        List<ClassNode> classes = new ArrayList<>();
        List<MethodNode> methods = new ArrayList<>();
        List<InheritanceEdge> inheritance = new ArrayList<>();
        List<CallEdge> calls = new ArrayList<>();
        List<ImportEdge> imports = new ArrayList<>();

        for (J.CompilationUnit cu : units) {
            // Normalise to forward slashes — Path.toString() yields backslashes
            // on Windows, which wouldn't match the forward-slash paths used as
            // keys everywhere else (blueprint files, migration order, MinIO).
            String filePath = cu.getSourcePath() != null
                    ? cu.getSourcePath().toString().replace('\\', '/') : null;

            // Imports.
            for (J.Import imp : cu.getImports()) {
                String fqn = imp.getTypeName();
                if (fqn == null || fqn.isBlank()) continue;
                String importer = enclosingTypeFqn(cu);
                if (importer != null) {
                    imports.add(new ImportEdge(importer, fqn, imp.isStatic()));
                }
            }

            for (J.ClassDeclaration cd : cu.getClasses()) {
                visitClass(cd, filePath, projectFqns, classes, methods, inheritance, calls);
            }
        }

        log.info("LST semantic graph: {} class(es), {} method(s), {} call(s), {} inheritance, {} import(s)",
                classes.size(), methods.size(), calls.size(), inheritance.size(), imports.size());
        return new SemanticGraph(classes, methods, inheritance, calls, imports);
    }

    // ── pass 1 ───────────────────────────────────────────────────────────────

    private void collectDeclaredFqns(J.ClassDeclaration cd, Set<String> out) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(cd.getType());
        if (type != null) out.add(type.getFullyQualifiedName());
        for (J.ClassDeclaration nested : nestedClasses(cd)) {
            collectDeclaredFqns(nested, out);
        }
    }

    // ── pass 2 ───────────────────────────────────────────────────────────────

    private void visitClass(J.ClassDeclaration cd, String filePath, Set<String> projectFqns,
                            List<ClassNode> classes, List<MethodNode> methods,
                            List<InheritanceEdge> inheritance, List<CallEdge> calls) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(cd.getType());
        String fqn = type != null ? type.getFullyQualifiedName() : cd.getSimpleName();
        String simple = cd.getSimpleName();
        String pkg = type != null ? type.getPackageName() : "";

        classes.add(new ClassNode(
                fqn, simple, pkg, mapKind(cd.getKind()), filePath, true,
                modifiers(cd.getModifiers()), annotationNames(cd.getLeadingAnnotations())));

        // extends / implements
        if (cd.getExtends() != null) {
            String superFqn = typeFqn(cd.getExtends().getType());
            if (superFqn != null) {
                inheritance.add(new InheritanceEdge(fqn, superFqn, InheritanceEdge.Kind.EXTENDS));
            }
        }
        if (cd.getImplements() != null) {
            for (var impl : cd.getImplements()) {
                String ifaceFqn = typeFqn(impl.getType());
                if (ifaceFqn != null) {
                    inheritance.add(new InheritanceEdge(fqn, ifaceFqn, InheritanceEdge.Kind.IMPLEMENTS));
                }
            }
        }

        // members: methods + nested classes + method calls
        for (org.openrewrite.java.tree.Statement st : cd.getBody().getStatements()) {
            if (st instanceof J.MethodDeclaration md) {
                methods.add(toMethodNode(fqn, md));
                collectCalls(fqn, md, projectFqns, calls);
            } else if (st instanceof J.ClassDeclaration nested) {
                visitClass(nested, filePath, projectFqns, classes, methods, inheritance, calls);
            }
        }
    }

    private MethodNode toMethodNode(String ownerFqn, J.MethodDeclaration md) {
        List<String> paramTypes = new ArrayList<>();
        for (org.openrewrite.java.tree.Statement p : md.getParameters()) {
            if (p instanceof J.VariableDeclarations vd) {
                String t = typeFqn(vd.getType());
                paramTypes.add(t != null ? t : "?");
            }
        }
        String returnType = md.getReturnTypeExpression() != null
                ? orQuestion(typeFqn(md.getReturnTypeExpression().getType()))
                : "void";
        return new MethodNode(ownerFqn, md.getSimpleName(), paramTypes, returnType,
                modifiers(md.getModifiers()));
    }

    /** Walk a method body collecting outgoing method-invocation edges. */
    private void collectCalls(String callerFqn, J.MethodDeclaration md,
                              Set<String> projectFqns, List<CallEdge> calls) {
        if (md.getBody() == null) return;
        new org.openrewrite.java.JavaIsoVisitor<List<CallEdge>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, List<CallEdge> acc) {
                JavaType.Method mt = mi.getMethodType();
                if (mt != null && mt.getDeclaringType() != null) {
                    String owner = mt.getDeclaringType().getFullyQualifiedName();
                    int arity = mt.getParameterTypes() != null ? mt.getParameterTypes().size() : 0;
                    boolean owned = projectFqns.contains(owner);
                    String calleeKey = owner + "#" + mi.getSimpleName() + "/" + arity;
                    int line = -1; // OpenRewrite positions need a PrintOutputCapture; arity+name is enough
                    acc.add(new CallEdge(callerFqn, line, calleeKey, owned));
                }
                return super.visitMethodInvocation(mi, acc);
            }
        }.visit(md.getBody(), calls);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<J.ClassDeclaration> nestedClasses(J.ClassDeclaration cd) {
        List<J.ClassDeclaration> out = new ArrayList<>();
        for (org.openrewrite.java.tree.Statement st : cd.getBody().getStatements()) {
            if (st instanceof J.ClassDeclaration nested) out.add(nested);
        }
        return out;
    }

    private static String typeFqn(JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null ? fq.getFullyQualifiedName() : null;
    }

    private static String orQuestion(String s) {
        return s != null ? s : "?";
    }

    private static String enclosingTypeFqn(J.CompilationUnit cu) {
        if (cu.getClasses().isEmpty()) return null;
        JavaType.FullyQualified t = TypeUtils.asFullyQualified(cu.getClasses().get(0).getType());
        return t != null ? t.getFullyQualifiedName() : cu.getClasses().get(0).getSimpleName();
    }

    private static List<String> modifiers(List<J.Modifier> mods) {
        List<String> out = new ArrayList<>();
        if (mods != null) for (J.Modifier m : mods) out.add(m.getType().name().toLowerCase());
        return out;
    }

    private static List<String> annotationNames(List<J.Annotation> annotations) {
        List<String> out = new ArrayList<>();
        if (annotations != null) {
            for (J.Annotation a : annotations) {
                out.add(a.getSimpleName());
            }
        }
        return out;
    }

    private static BlueprintFile.Kind mapKind(J.ClassDeclaration.Kind.Type kind) {
        if (kind == null) return BlueprintFile.Kind.CLASS;
        return switch (kind) {
            case Interface -> BlueprintFile.Kind.INTERFACE;
            case Enum -> BlueprintFile.Kind.ENUM;
            case Record -> BlueprintFile.Kind.RECORD;
            case Annotation -> BlueprintFile.Kind.ANNOTATION;
            default -> BlueprintFile.Kind.CLASS;
        };
    }
}
