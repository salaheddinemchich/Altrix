package com.altrix.orchestrator.infrastructure.contract;

import com.altrix.orchestrator.domain.model.contract.ContractViolation;
import com.altrix.orchestrator.domain.model.contract.ContractViolationKind;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-file contract validator (Project Semantic Index, lightweight).
 *
 * <p>Goal — catch the inconsistencies the file-by-file LLM migrator
 * produces BEFORE they reach the Docker sandbox, so we can repair them
 * with surgical edits instead of paying the full Maven compile cost.
 *
 * <p>This is NOT a full Java compiler.  We deliberately stay inside the
 * project's own base package and only enforce intra-project contracts:
 * external types (kafka-clients, jakarta-api, …) are out of scope because
 * verifying them needs a real classpath.  Within the project we run a
 * JavaParser pass that builds:
 * <ul>
 *   <li>a type index — {@code simpleName → CompilationUnit}</li>
 *   <li>a method index — for every type, the declared method signatures
 *       (name + arity); recurses into superclass / interface declarations
 *       on the same index so the {@code @Override} / "missing impl"
 *       check can resolve transitively.</li>
 *   <li>a constructor index — name → arities</li>
 *   <li>a visibility index — name → {@code public ?}</li>
 * </ul>
 *
 * <p>Then visits each file and surfaces:
 * <ul>
 *   <li>file name vs public type name mismatch</li>
 *   <li>unresolved intra-project imports</li>
 *   <li>concrete {@code implements I} where some abstract method of {@code I}
 *       is not provided</li>
 *   <li>method calls on locally-typed receivers where the callee doesn't
 *       declare the method</li>
 *   <li>{@code new Foo(...)} arity mismatch for intra-project {@code Foo}</li>
 *   <li>{@code @Override} where the parent has no matching name + arity</li>
 *   <li>type referenced from a foreign package but declared package-private</li>
 * </ul>
 *
 * <p>The validator is pure / stateless / thread-safe.  Bean scope is the
 * Spring default singleton.
 */
@Slf4j
@Component
public class ContractValidator {

    /**
     * Validates every {@code .java} file in {@code files} as a single
     * compilation unit set and returns one violation list.  Non-Java
     * entries are silently ignored.  An empty list means the artifact
     * is internally consistent — the caller can ship it to sandbox.
     *
     * @param files  path → source content map of the migrated artifact.
     *               Paths are used verbatim in violation reports, so they
     *               should be the relative paths inside the project tree.
     */
    public List<ContractViolation> validate(Map<String, String> files) {
        if (files == null || files.isEmpty()) return List.of();

        // ── 1. Parse every Java file once ────────────────────────────────
        Map<String, CompilationUnit> parsed = new LinkedHashMap<>();
        JavaParser parser = new JavaParser();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            if (path == null || !path.toLowerCase().endsWith(".java")) continue;
            String content = entry.getValue();
            if (content == null || content.isBlank()) continue;
            try {
                ParseResult<CompilationUnit> result = parser.parse(content);
                result.getResult().ifPresent(cu -> parsed.put(path, cu));
                // A parse failure here just means we skip semantic checks
                // for that file — the structural validator upstream already
                // catches malformed Java.  Don't add a violation: the
                // sandbox compile will surface the syntax error clearly.
            } catch (Exception e) {
                log.debug("ContractValidator: could not parse '{}' — skipping ({})",
                        path, e.getMessage());
            }
        }
        if (parsed.isEmpty()) return List.of();

        // ── 2. Build the project semantic index ──────────────────────────
        ProjectIndex index = buildIndex(parsed);

        // ── 3. Per-file checks ───────────────────────────────────────────
        List<ContractViolation> all = new ArrayList<>();
        for (Map.Entry<String, CompilationUnit> entry : parsed.entrySet()) {
            String path = entry.getKey();
            CompilationUnit cu = entry.getValue();
            checkFileClassMismatch(path, cu, all);
            checkImports(path, cu, index, all);
            checkUnresolvedTypeReferences(path, cu, index, all);
            checkInterfaceImplementations(path, cu, index, all);
            checkInterfaceConformanceTyped(path, cu, index, all);
            checkConstructorArity(path, cu, index, all);
            checkMethodCalls(path, cu, index, all);
            checkOverrides(path, cu, index, all);
            checkCrossPackagePackagePrivateUse(path, cu, index, all);
        }
        return Collections.unmodifiableList(all);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Project semantic index
    // ──────────────────────────────────────────────────────────────────────

    /**
     * In-memory project model.  Built once per {@link #validate(Map)} call;
     * cheap to throw away (typical project has ~50 files, JavaParser parses
     * in single-digit ms each).
     */
    private static final class ProjectIndex {
        /** {@code simpleName → declaring CompilationUnit}.  First wins on collisions. */
        final Map<String, CompilationUnit> typeByName = new HashMap<>();
        /** {@code simpleName → fully-qualified name} when the type has a package. */
        final Map<String, String> fqnByName = new HashMap<>();
        /** {@code simpleName → declared method (name, arity) pairs}. */
        final Map<String, Set<MethodSig>> methodsByType = new HashMap<>();
        /** {@code simpleName → declared methods with full parameter + return types} (contract-lock check). */
        final Map<String, Set<MethodFull>> methodsFullByType = new HashMap<>();
        /** {@code simpleName → declared constructor arities}. */
        final Map<String, Set<Integer>> ctorAritiesByType = new HashMap<>();
        /** {@code simpleName → true iff the top-level type is public}. */
        final Map<String, Boolean> publicByType = new HashMap<>();
        /** {@code simpleName → package name} (empty string for default package). */
        final Map<String, String> packageByType = new HashMap<>();
    }

    /**
     * Method signature for index purposes — name + arity.  We do NOT track
     * parameter types because the migrator routinely changes them (Pub/Sub
     * → Kafka type swap) and overload resolution by Java's actual rules
     * needs full type information we don't have.  Name + arity is a
     * pragmatic compromise: it catches "callers use the old method name",
     * "interface method not implemented", and "@Override on a method that
     * doesn't exist", which are the dominant patterns.
     */
    private record MethodSig(String name, int arity) {}

    /**
     * Full method signature — name + ordered parameter types + return type,
     * all reduced to simple names (generics/array/package stripped).  Used by
     * the contract-lock conformance check to compare an implementation's
     * methods against the interface contract by TYPE, not just name+arity.
     */
    private record MethodFull(String name, List<String> paramTypes, String returnType) {
        /** Identity for "is this method present?" — name + parameter types (return type excluded; Java forbids overload-by-return). */
        String key() { return name + "(" + String.join(",", paramTypes) + ")"; }
        /** Human-readable signature for the violation message. */
        String render() { return name + "(" + String.join(", ", paramTypes) + "): " + returnType; }
    }

    private ProjectIndex buildIndex(Map<String, CompilationUnit> parsed) {
        ProjectIndex idx = new ProjectIndex();
        for (CompilationUnit cu : parsed.values()) {
            String pkg = cu.getPackageDeclaration()
                    .map(p -> p.getName().toString()).orElse("");
            for (TypeDeclaration<?> td : cu.getTypes()) {
                String simple = td.getNameAsString();
                idx.typeByName.putIfAbsent(simple, cu);
                idx.fqnByName.putIfAbsent(simple, pkg.isEmpty() ? simple : pkg + "." + simple);
                idx.packageByType.putIfAbsent(simple, pkg);
                idx.publicByType.putIfAbsent(simple, td.getModifiers().contains(Modifier.publicModifier()));
                indexMembers(td, idx);
            }
        }
        return idx;
    }

    private void indexMembers(TypeDeclaration<?> td, ProjectIndex idx) {
        String simple = td.getNameAsString();
        Set<MethodSig> sigs = idx.methodsByType.computeIfAbsent(simple, k -> new HashSet<>());
        Set<MethodFull> fullSigs = idx.methodsFullByType.computeIfAbsent(simple, k -> new HashSet<>());
        Set<Integer> arities = idx.ctorAritiesByType.computeIfAbsent(simple, k -> new HashSet<>());
        td.getMethods().forEach(m -> {
            sigs.add(new MethodSig(m.getNameAsString(), m.getParameters().size()));
            List<String> params = new ArrayList<>();
            m.getParameters().forEach(p -> params.add(contractTypeName(p.getType().toString())));
            fullSigs.add(new MethodFull(m.getNameAsString(), params, contractTypeName(m.getType().toString())));
        });
        td.getConstructors().forEach(c -> arities.add(c.getParameters().size()));
        // For records: synthetic accessors named after components.  JavaParser
        // does not synthesise them; we add them so callers of `rec.foo()`
        // don't get false-positive UNKNOWN_METHOD_CALL.
        if (td instanceof com.github.javaparser.ast.body.RecordDeclaration rec) {
            rec.getParameters().forEach(p -> sigs.add(new MethodSig(p.getNameAsString(), 0)));
            // The canonical record constructor arity is the parameter count.
            arities.add(rec.getParameters().size());
        }
        // Lombok shorthand — when @Data / @Getter / @Setter / @Builder / @Value
        // are present we credit the type with the generated accessors so the
        // validator stops flagging `payment.getId()`.  Pragmatic and safe:
        // a false credit only RELAXES checks, never tightens them.
        Set<String> annotations = new HashSet<>();
        td.getAnnotations().forEach(a -> annotations.add(a.getNameAsString()));
        boolean wantsGetter  = annotations.contains("Getter") || annotations.contains("Data") || annotations.contains("Value");
        boolean wantsSetter  = annotations.contains("Setter") || annotations.contains("Data");
        boolean wantsBuilder = annotations.contains("Builder") || annotations.contains("SuperBuilder") || annotations.contains("Value");
        if (wantsBuilder) {
            sigs.add(new MethodSig("builder", 0));
        }
        if (wantsGetter || wantsSetter) {
            td.getFields().forEach(f -> f.getVariables().forEach(v -> {
                String name = v.getNameAsString();
                String cap  = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
                if (wantsGetter) sigs.add(new MethodSig("get" + cap, 0));
                if (wantsGetter) sigs.add(new MethodSig("is" + cap, 0));
                if (wantsSetter) sigs.add(new MethodSig("set" + cap, 1));
            }));
        }
        // Nested types — index recursively under their simple name.
        td.getMembers().forEach(m -> {
            if (m instanceof TypeDeclaration<?> nested) {
                idx.publicByType.putIfAbsent(nested.getNameAsString(),
                        nested.getModifiers().contains(Modifier.publicModifier()));
                indexMembers(nested, idx);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Per-file checks
    // ──────────────────────────────────────────────────────────────────────

    private void checkFileClassMismatch(String path, CompilationUnit cu,
                                        List<ContractViolation> out) {
        String fileName = baseName(path);
        if (fileName == null) return;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td.getModifiers().contains(Modifier.publicModifier())) {
                String declared = td.getNameAsString();
                if (!declared.equals(fileName)) {
                    out.add(new ContractViolation(
                            ContractViolationKind.FILE_CLASS_MISMATCH,
                            path, td.getBegin().map(p -> p.line).orElse(-1),
                            declared,
                            "Public type '" + declared + "' declared in file '"
                                    + fileName + ".java' — Java requires the names to match. "
                                    + "Rename the type back to '" + fileName + "'."));
                }
                return; // only one public top-level allowed; skip the rest
            }
        }
    }

    private void checkImports(String path, CompilationUnit cu, ProjectIndex idx,
                              List<ContractViolation> out) {
        String basePkg = inferBasePackage(idx);
        if (basePkg.isEmpty()) return;
        cu.getImports().forEach(imp -> {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) return;
            if (imp.isStatic()) return;
            if (!name.startsWith(basePkg + ".") && !name.equals(basePkg)) return;
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (!idx.typeByName.containsKey(simple)) {
                out.add(new ContractViolation(
                        ContractViolationKind.UNRESOLVED_INTRA_PROJECT_IMPORT,
                        path,
                        imp.getBegin().map(p -> p.line).orElse(-1),
                        name,
                        "Imported '" + name + "' but no type '" + simple
                                + "' is declared anywhere in the artifact."));
            }
        });
    }

    /**
     * Common {@code java.lang} types usable WITHOUT an import — the
     * allow-list that keeps {@link #checkUnresolvedTypeReferences} from
     * flagging {@code String}, {@code Object}, … as unresolved.
     */
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "String", "Object", "Integer", "Long", "Double", "Float", "Short", "Byte",
            "Boolean", "Character", "Number", "Math", "System", "Thread", "Runnable",
            "Void", "Class", "Enum", "Iterable", "Comparable", "CharSequence",
            "StringBuilder", "StringBuffer", "Exception", "RuntimeException", "Throwable",
            "Error", "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
            "SafeVarargs", "Cloneable", "AutoCloseable", "Record", "Process", "Appendable");

    /**
     * Orphaned-type-reference check — catches a type used in a type position
     * (parameter, return, field, generic arg, {@code extends}/{@code
     * implements}, {@code new X()}) by simple name that resolves to NOTHING:
     * not imported, not {@code java.lang}, not a generic type variable, and
     * not declared anywhere in the project.
     *
     * <p>This is the failure mode {@link #checkImports} misses: a
     * <b>same-package</b> reference needs no import, so when the migrator
     * renames a type in one file only (e.g. {@code AltrixPubsubMessage →
     * AltrixKafkaMessage} in an interface but not the class), the dangling
     * reference compiles to "cannot find symbol" — invisible to the import
     * check because there's no import line to flag.
     *
     * <p>Conservative to avoid false positives: skips files with any
     * wildcard import (can't know what they bring in), qualified references
     * ({@code com.foo.Bar}, {@code Map.Entry}), single-letter generics, and
     * lowercase names.  Reports each missing type once per file and lists
     * same-package project types as rename candidates so the repairer can
     * re-point the reference at the type that actually exists.
     */
    private void checkUnresolvedTypeReferences(String path, CompilationUnit cu,
                                               ProjectIndex idx,
                                               List<ContractViolation> out) {
        // A wildcard import could legitimately bring in any of these names —
        // bail rather than risk a false positive.
        boolean hasWildcard = cu.getImports().stream()
                .anyMatch(com.github.javaparser.ast.ImportDeclaration::isAsterisk);
        if (hasWildcard) return;

        Set<String> imported = new HashSet<>();
        cu.getImports().forEach(imp -> {
            if (imp.isAsterisk() || imp.isStatic()) return;
            String n = imp.getNameAsString();
            imported.add(n.substring(n.lastIndexOf('.') + 1));
        });

        Set<String> typeParams = new HashSet<>();
        cu.findAll(com.github.javaparser.ast.type.TypeParameter.class)
                .forEach(tp -> typeParams.add(tp.getNameAsString()));

        Set<String> declaredHere = new HashSet<>();
        cu.findAll(TypeDeclaration.class).forEach(td -> declaredHere.add(td.getNameAsString()));

        String pkg = cu.getPackageDeclaration().map(p -> p.getName().toString()).orElse("");

        Set<String> reported = new HashSet<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> {
            if (t.getScope().isPresent()) return;                 // qualified — out of scope
            String simple = t.getNameAsString();
            if (simple.isEmpty() || reported.contains(simple)) return;
            if (typeParams.contains(simple)) return;              // generic type variable
            if (simple.length() == 1 && Character.isUpperCase(simple.charAt(0))) return; // T, E, K…
            if (!Character.isUpperCase(simple.charAt(0))) return; // not a class-shaped name
            if (JAVA_LANG_TYPES.contains(simple)) return;
            if (imported.contains(simple)) return;
            if (declaredHere.contains(simple)) return;

            // Does the type exist somewhere in the project?
            if (idx.typeByName.containsKey(simple)) {
                String declaredPkg = idx.packageByType.get(simple);
                // Same package (or a nested type we can't place) needs no import — fine.
                if (declaredPkg == null || declaredPkg.equals(pkg)) return;
                // Different package + not imported → missing intra-project import.
                // This is the failure mode `checkImports` can't see: the type
                // IS a real project type, it's just used without the import
                // that brings it across the package boundary (e.g. a `tasks`
                // class extending `pubsub.RetryTask` after the migrator
                // dropped the import).
                reported.add(simple);
                String fqn = idx.fqnByName.getOrDefault(simple, declaredPkg + "." + simple);
                out.add(new ContractViolation(
                        ContractViolationKind.MISSING_IMPORT,
                        path,
                        t.getBegin().map(p -> p.line).orElse(-1),
                        simple,
                        "Type '" + simple + "' is used but not imported; it is declared in "
                                + "package '" + declaredPkg + "'. Add 'import " + fqn + ";' "
                                + "(do not move or rename the type)."));
                return;
            }

            // Not declared anywhere in the project — orphaned reference
            // (typically an inconsistent rename).
            reported.add(simple);
            String candidates = samePackageCandidates(pkg, declaredHere, idx);
            out.add(new ContractViolation(
                    ContractViolationKind.MISSING_IMPORT,
                    path,
                    t.getBegin().map(p -> p.line).orElse(-1),
                    simple,
                    "Type '" + simple + "' is referenced but is not imported and is not "
                            + "declared anywhere in the project (likely an inconsistent rename). "
                            + (candidates.isEmpty()
                                ? "Re-point it at an existing project type"
                                : "Existing types in package '" + pkg + "': " + candidates
                                  + ". Re-point the reference at the correct existing type")
                            + ". If none matches, it is likely an obsolete source-platform "
                            + "concept with no target equivalent — replace its uses with "
                            + "Object or remove the member (consistent with sibling methods); "
                            + "do not invent the type."));
        });
    }

    /** Up to 8 project types declared in {@code pkg}, excluding this file's own types. */
    private static String samePackageCandidates(String pkg, Set<String> exclude, ProjectIndex idx) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> e : idx.packageByType.entrySet()) {
            if (pkg.equals(e.getValue()) && !exclude.contains(e.getKey())) {
                names.add(e.getKey());
                if (names.size() >= 8) break;
            }
        }
        Collections.sort(names);
        return String.join(", ", names);
    }

    private void checkInterfaceImplementations(String path, CompilationUnit cu,
                                               ProjectIndex idx,
                                               List<ContractViolation> out) {
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration cls)) continue;
            if (cls.isInterface() || cls.isAbstract()) continue;
            // Concrete class — every method on every implemented interface
            // must be provided by the class itself OR by a concrete parent
            // (via `extends`).  We deliberately exclude the implemented
            // interface from the "provided" set: that's what we're checking
            // against, so including it would make every check vacuously pass.
            Set<MethodSig> provided = classOwnAndExtendedMethods(cls.getNameAsString(), idx);
            for (ClassOrInterfaceType impl : cls.getImplementedTypes()) {
                String iface = impl.getNameAsString();
                Set<MethodSig> required = effectiveMethods(iface, idx);
                if (required == null || required.isEmpty()) continue; // external or empty interface
                for (MethodSig sig : required) {
                    if (!provided.contains(sig)) {
                        out.add(new ContractViolation(
                                ContractViolationKind.MISSING_INTERFACE_METHOD,
                                path,
                                cls.getBegin().map(p -> p.line).orElse(-1),
                                sig.name() + "/" + sig.arity(),
                                "Class '" + cls.getNameAsString() + "' implements '"
                                        + iface + "' but does not declare method '"
                                        + sig.name() + "' (arity " + sig.arity() + ")."));
                    }
                }
            }
        }
    }

    /**
     * Methods a CLASS provides for the purposes of the interface-impl check:
     * its own declared methods plus methods inherited transitively through
     * {@code extends} (but NOT through {@code implements} — we are about
     * to compare those).
     */
    private Set<MethodSig> classOwnAndExtendedMethods(String typeName, ProjectIndex idx) {
        Set<MethodSig> all = new HashSet<>();
        CompilationUnit cu = idx.typeByName.get(typeName);
        Set<MethodSig> own = idx.methodsByType.get(typeName);
        if (own != null) all.addAll(own);
        if (cu == null) return all;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!td.getNameAsString().equals(typeName)) continue;
            if (td instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t ->
                        all.addAll(classOwnAndExtendedMethods(t.getNameAsString(), idx)));
            }
        }
        return all;
    }

    /** All method signatures available on a type, walking up implemented + extended supertypes within the project. */
    private Set<MethodSig> effectiveMethods(String typeName, ProjectIndex idx) {
        Set<MethodSig> all = new HashSet<>();
        CompilationUnit cu = idx.typeByName.get(typeName);
        Set<MethodSig> own = idx.methodsByType.get(typeName);
        if (own != null) all.addAll(own);
        if (cu == null) return all;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!td.getNameAsString().equals(typeName)) continue;
            if (td instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t -> all.addAll(effectiveMethods(t.getNameAsString(), idx)));
                c.getImplementedTypes().forEach(t -> all.addAll(effectiveMethods(t.getNameAsString(), idx)));
            }
        }
        return all;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Contract-lock: type-aware interface conformance
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Type-aware interface conformance — the enforcement half of
     * "contract-locked migration".  Where {@link #checkInterfaceImplementations}
     * only checks name+arity, this compares the implementation's methods
     * against the interface contract by FULL parameter types.
     *
     * <p>Catches the dominant cross-file drift the per-file migrator produces:
     * the interface migrates {@code publish(PubsubTopic,…)} to
     * {@code publish(String,…)} but the implementation keeps
     * {@code publish(PubsubTopic,…)} — same name, different types, so it
     * implements NONE of the interface methods ("not abstract, does not
     * override").  We emit one violation per drifted method naming the EXACT
     * required signature, and the repairer is told the interface is the
     * contract: conform the implementation to it.
     *
     * <p>Only fires when a same-NAME method exists on the class but with
     * different parameter types (true drift).  A method missing entirely is
     * left to {@link #checkInterfaceImplementations} (name+arity), so the two
     * checks don't double-report.
     */
    private void checkInterfaceConformanceTyped(String path, CompilationUnit cu,
                                                ProjectIndex idx,
                                                List<ContractViolation> out) {
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration cls)) continue;
            if (cls.isInterface() || cls.isAbstract()) continue;

            Set<MethodFull> provided = classOwnAndExtendedMethodsFull(cls.getNameAsString(), idx);
            Set<String> providedKeys  = new HashSet<>();
            Set<String> providedNames = new HashSet<>();
            for (MethodFull mf : provided) { providedKeys.add(mf.key()); providedNames.add(mf.name()); }

            for (ClassOrInterfaceType impl : cls.getImplementedTypes()) {
                String iface = impl.getNameAsString();
                Set<MethodFull> required = effectiveMethodsFull(iface, idx);
                if (required.isEmpty()) continue; // external or empty interface
                for (MethodFull m : required) {
                    if (providedKeys.contains(m.key())) continue;     // satisfied exactly
                    if (!providedNames.contains(m.name())) continue;  // missing → name+arity check owns it
                    out.add(new ContractViolation(
                            ContractViolationKind.INTERFACE_SIGNATURE_MISMATCH,
                            path,
                            cls.getBegin().map(p -> p.line).orElse(-1),
                            cls.getNameAsString() + "." + m.key(),
                            "Class '" + cls.getNameAsString() + "' implements '" + iface
                                    + "' but its '" + m.name() + "' does not match the interface "
                                    + "contract. Required EXACT signature: " + m.render()
                                    + ". The interface is the contract — change THIS implementation's "
                                    + "parameter/return types to match it exactly and adjust the body "
                                    + "accordingly (do NOT change the interface)."));
                }
            }
        }
    }

    /** Full-signature analogue of {@link #classOwnAndExtendedMethods}. */
    private Set<MethodFull> classOwnAndExtendedMethodsFull(String typeName, ProjectIndex idx) {
        Set<MethodFull> all = new HashSet<>();
        Set<MethodFull> own = idx.methodsFullByType.get(typeName);
        if (own != null) all.addAll(own);
        CompilationUnit cu = idx.typeByName.get(typeName);
        if (cu == null) return all;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!td.getNameAsString().equals(typeName)) continue;
            if (td instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t ->
                        all.addAll(classOwnAndExtendedMethodsFull(t.getNameAsString(), idx)));
            }
        }
        return all;
    }

    /** Full-signature analogue of {@link #effectiveMethods} (walks extends + implements). */
    private Set<MethodFull> effectiveMethodsFull(String typeName, ProjectIndex idx) {
        Set<MethodFull> all = new HashSet<>();
        Set<MethodFull> own = idx.methodsFullByType.get(typeName);
        if (own != null) all.addAll(own);
        CompilationUnit cu = idx.typeByName.get(typeName);
        if (cu == null) return all;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!td.getNameAsString().equals(typeName)) continue;
            if (td instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t -> all.addAll(effectiveMethodsFull(t.getNameAsString(), idx)));
                c.getImplementedTypes().forEach(t -> all.addAll(effectiveMethodsFull(t.getNameAsString(), idx)));
            }
        }
        return all;
    }

    private void checkConstructorArity(String path, CompilationUnit cu, ProjectIndex idx,
                                       List<ContractViolation> out) {
        cu.findAll(ObjectCreationExpr.class).forEach(oce -> {
            String typeName = oce.getType().getNameAsString();
            Set<Integer> arities = idx.ctorAritiesByType.get(typeName);
            if (arities == null || arities.isEmpty()) return; // external or implicit no-arg
            int actual = oce.getArguments().size();
            if (!arities.contains(actual)) {
                out.add(new ContractViolation(
                        ContractViolationKind.BAD_CONSTRUCTOR_ARITY,
                        path,
                        oce.getBegin().map(p -> p.line).orElse(-1),
                        typeName + "/" + actual,
                        "new " + typeName + "(...) with " + actual + " argument(s), but "
                                + typeName + " declares constructor(s) of arity " + arities + "."));
            }
        });
    }

    /**
     * Method-call check.  Best-effort by design — we can't resolve every
     * receiver type without a full symbol solver.  We catch the high-signal
     * pattern: a same-file local variable or field whose declared type is
     * a project type, then {@code local.foo()} where the project type has
     * no method named {@code foo} at any arity.  This is exactly the
     * "caller still uses the old method name" failure mode.
     */
    private void checkMethodCalls(String path, CompilationUnit cu, ProjectIndex idx,
                                  List<ContractViolation> out) {
        Map<String, String> localTypes = collectLocalTypes(cu);
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            call.getScope().ifPresent(scope -> {
                if (!(scope instanceof NameExpr nameExpr)) return;
                String typeName = localTypes.get(nameExpr.getNameAsString());
                if (typeName == null) return;
                Set<MethodSig> available = effectiveMethods(typeName, idx);
                if (available == null || available.isEmpty()) return;
                String methodName = call.getNameAsString();
                boolean knownName = available.stream().anyMatch(m -> m.name().equals(methodName));
                if (!knownName) {
                    out.add(new ContractViolation(
                            ContractViolationKind.UNKNOWN_METHOD_CALL,
                            path,
                            call.getBegin().map(p -> p.line).orElse(-1),
                            typeName + "." + methodName,
                            "Call '" + nameExpr.getNameAsString() + "." + methodName
                                    + "(...)' — type '" + typeName
                                    + "' declares no method named '" + methodName + "'."));
                }
            });
        });
    }

    /**
     * Maps every {@code field} and constructor / method parameter and local
     * variable declaration in the file to the simple name of its declared
     * type, but only when that type is a known project type.  Used by the
     * method-call check to resolve receivers.
     */
    private Map<String, String> collectLocalTypes(CompilationUnit cu) {
        Map<String, String> out = new HashMap<>();
        cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).forEach(f -> {
            String typeName = simpleTypeName(f.getElementType().toString());
            f.getVariables().forEach(v -> out.put(v.getNameAsString(), typeName));
        });
        cu.findAll(com.github.javaparser.ast.body.Parameter.class).forEach(p ->
                out.put(p.getNameAsString(), simpleTypeName(p.getType().toString())));
        cu.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(v ->
                out.put(v.getNameAsString(), simpleTypeName(v.getType().toString())));
        return out;
    }

    /**
     * Normalises a type for contract comparison + display: strips package and
     * generic parameters but PRESERVES array-ness ({@code byte[]} stays
     * {@code byte[]}, distinct from {@code byte}).  Used by the typed
     * conformance check so {@code publish(String,byte[])} reads accurately in
     * the violation message and compares correctly.
     */
    private static String contractTypeName(String typeText) {
        String t = typeText.trim();
        boolean array = false;
        int lt = t.indexOf('<');
        if (lt > 0) {                       // strip generics, but note a trailing [] after them
            int close = t.lastIndexOf('>');
            if (close >= 0 && t.substring(close + 1).contains("[")) array = true;
            t = t.substring(0, lt);
        }
        if (t.contains("[")) { array = true; t = t.substring(0, t.indexOf('[')); }
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(dot + 1);
        return t.trim() + (array ? "[]" : "");
    }

    private static String simpleTypeName(String typeText) {
        // Strip generics and array brackets: List<Foo> → List ; Foo[] → Foo
        int lt = typeText.indexOf('<');
        if (lt > 0) typeText = typeText.substring(0, lt);
        int br = typeText.indexOf('[');
        if (br > 0) typeText = typeText.substring(0, br);
        int dot = typeText.lastIndexOf('.');
        if (dot > 0) typeText = typeText.substring(dot + 1);
        return typeText.trim();
    }

    private void checkOverrides(String path, CompilationUnit cu, ProjectIndex idx,
                                List<ContractViolation> out) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            boolean hasOverride = m.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("Override"));
            if (!hasOverride) return;
            String name = m.getNameAsString();
            int arity = m.getParameters().size();
            String enclosing = m.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse(null);
            if (enclosing == null) return;
            // Walk this type's supertypes (within the project) and check for a matching sig.
            Set<MethodSig> parents = parentMethods(enclosing, idx);
            if (parents.isEmpty()) return; // external supertype — can't tell
            boolean matched = parents.stream()
                    .anyMatch(sig -> sig.name().equals(name) && sig.arity() == arity);
            if (!matched) {
                out.add(new ContractViolation(
                        ContractViolationKind.INVALID_OVERRIDE,
                        path,
                        m.getBegin().map(p -> p.line).orElse(-1),
                        enclosing + "." + name + "/" + arity,
                        "@Override on '" + name + "' (arity " + arity + ") but no supertype within the project declares it."));
            }
        });
    }

    /** All method sigs from supertypes (no own type) — used by {@code @Override} check. */
    private Set<MethodSig> parentMethods(String typeName, ProjectIndex idx) {
        Set<MethodSig> out = new HashSet<>();
        CompilationUnit cu = idx.typeByName.get(typeName);
        if (cu == null) return out;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!td.getNameAsString().equals(typeName)) continue;
            if (td instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t -> out.addAll(effectiveMethods(t.getNameAsString(), idx)));
                c.getImplementedTypes().forEach(t -> out.addAll(effectiveMethods(t.getNameAsString(), idx)));
            }
        }
        return out;
    }

    private void checkCrossPackagePackagePrivateUse(String path, CompilationUnit cu,
                                                    ProjectIndex idx,
                                                    List<ContractViolation> out) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getName().toString()).orElse("");
        cu.getImports().forEach(imp -> {
            if (imp.isAsterisk() || imp.isStatic()) return;
            String name = imp.getNameAsString();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            String declaredPkg = idx.packageByType.get(simple);
            if (declaredPkg == null) return; // not in project — UNRESOLVED already flagged
            if (declaredPkg.equals(pkg)) return; // same package, package-private OK
            Boolean pub = idx.publicByType.get(simple);
            if (pub == null || pub) return;
            out.add(new ContractViolation(
                    ContractViolationKind.NON_PUBLIC_TYPE_USED_CROSS_PACKAGE,
                    path,
                    imp.getBegin().map(p -> p.line).orElse(-1),
                    name,
                    "Imported '" + name + "' from package '" + pkg
                            + "' but the type is declared package-private in '"
                            + declaredPkg + "'.  Add the 'public' modifier to the declaration."));
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Heuristic: shortest declared package prefix shared by everything we indexed.
     * Empty when the index spans unrelated roots (defensive — disables the
     * intra-project checks rather than flagging false positives).
     */
    private static String inferBasePackage(ProjectIndex idx) {
        String common = null;
        for (String pkg : new LinkedHashSet<>(idx.packageByType.values())) {
            if (pkg == null || pkg.isEmpty()) continue;
            common = common == null ? pkg : longestCommonPackagePrefix(common, pkg);
            if (common.isEmpty()) return "";
        }
        return common == null ? "" : common;
    }

    private static String longestCommonPackagePrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int i = 0;
        while (i < pa.length && i < pb.length && pa[i].equals(pb[i])) i++;
        if (i == 0) return "";
        StringBuilder sb = new StringBuilder(pa[0]);
        for (int j = 1; j < i; j++) sb.append('.').append(pa[j]);
        return sb.toString();
    }

    private static String baseName(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String fileName = path.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? null : fileName.substring(0, dot);
    }

    /** Convenience used by callers that want a "did anything fail?" answer. */
    public boolean isClean(Map<String, String> files) {
        return validate(files).isEmpty();
    }

    /** Group violations by file path; preserves insertion order. */
    public Map<String, List<ContractViolation>> groupByFile(List<ContractViolation> violations) {
        if (violations == null || violations.isEmpty()) return Map.of();
        Map<String, List<ContractViolation>> grouped = new LinkedHashMap<>();
        for (ContractViolation v : violations) {
            grouped.computeIfAbsent(v.filePath(), k -> new ArrayList<>()).add(v);
        }
        return grouped;
    }

    /** Diagnostic — formats every violation as one line. */
    public String render(List<ContractViolation> violations) {
        if (violations == null || violations.isEmpty()) return "(clean)";
        StringBuilder sb = new StringBuilder();
        for (ContractViolation v : violations) sb.append(v.toLine()).append('\n');
        return sb.toString();
    }

    /** Used in tests / logs to confirm which kinds the validator surfaced. */
    public Set<ContractViolationKind> kinds(List<ContractViolation> violations) {
        if (violations == null || violations.isEmpty()) return Set.of();
        Set<ContractViolationKind> kinds = new LinkedHashSet<>();
        for (ContractViolation v : violations) kinds.add(v.kind());
        return kinds;
    }

    /** Convenience for tests that want a single violation by kind. */
    public Optional<ContractViolation> firstByKind(List<ContractViolation> violations,
                                                   ContractViolationKind kind) {
        if (violations == null) return Optional.empty();
        for (ContractViolation v : violations) if (v.kind() == kind) return Optional.of(v);
        return Optional.empty();
    }
}
