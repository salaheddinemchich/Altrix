package com.altrix.orchestrator.infrastructure.contract;

import com.altrix.orchestrator.domain.model.contract.ContractViolation;
import com.altrix.orchestrator.domain.model.contract.ContractViolationKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the validator against the inconsistency patterns we have
 * observed in real migrated artifacts (the user's "test-altrix" /
 * Pub/Sub→Kafka projects).
 *
 * <p>Each test names the specific compile-error class it is replaying so
 * future maintainers can grep for the symptom.
 */
class ContractValidatorTest {

    private final ContractValidator validator = new ContractValidator();

    // ── File / class name mismatch ──────────────────────────────────────────

    /**
     * Real failure: file {@code IGoogleErrorConverter.java} contains
     * {@code public interface IKafkaErrorConverter} — javac aborts the
     * build with "bad source file ... should be declared in a file named
     * IKafkaErrorConverter.java".
     */
    @Test
    void fileClassMismatch_detectsRenameInsideFile() {
        Map<String, String> files = Map.of(
                "src/main/java/p/IGoogleErrorConverter.java",
                "package p;\npublic interface IKafkaErrorConverter {}");
        List<ContractViolation> v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(ContractViolationKind.FILE_CLASS_MISMATCH);
        assertThat(validator.firstByKind(v, ContractViolationKind.FILE_CLASS_MISMATCH).orElseThrow()
                .symbol()).isEqualTo("IKafkaErrorConverter");
    }

    @Test
    void fileClassMismatch_passesWhenNamesMatch() {
        Map<String, String> files = Map.of(
                "p/Foo.java",
                "package p;\npublic class Foo {}");
        assertThat(validator.validate(files)).isEmpty();
    }

    @Test
    void fileClassMismatch_ignoresPackagePrivateType() {
        Map<String, String> files = Map.of(
                "p/Helper.java",
                "package p;\nclass Worker {}"); // not public — legal
        assertThat(validator.kinds(validator.validate(files)))
                .doesNotContain(ContractViolationKind.FILE_CLASS_MISMATCH);
    }

    // ── Unresolved intra-project import ─────────────────────────────────────

    @Test
    void unresolvedImport_flagsRefToTypeNeverDeclared() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/A.java",
                "package com.x.a;\nimport com.x.b.Missing;\npublic class A {}");
        files.put("p/B.java",
                "package com.x.b;\npublic class Other {}");
        var v = validator.validate(files);
        assertThat(validator.kinds(v))
                .contains(ContractViolationKind.UNRESOLVED_INTRA_PROJECT_IMPORT);
    }

    // ── Missing interface method ────────────────────────────────────────────

    /**
     * Real failure: the migrator changed {@code PubsubService} to declare
     * {@code testACLsOnKafkaTopic(String, List<String>)} but never updated
     * {@code PubsubServiceImpl} — javac:
     * "PubsubServiceImpl is not abstract and does not override abstract method".
     */
    @Test
    void missingInterfaceMethod_detectsImplMissingMethodOnIface() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/PubsubService.java", """
                package com.x.pubsub;
                public interface PubsubService {
                    void testACLsOnKafkaTopic(String topic, java.util.List<String> perms);
                }
                """);
        files.put("p/PubsubServiceImpl.java", """
                package com.x.pubsub;
                public class PubsubServiceImpl implements PubsubService {
                    // Forgot to implement testACLsOnKafkaTopic.
                }
                """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(ContractViolationKind.MISSING_INTERFACE_METHOD);
        assertThat(validator.firstByKind(v, ContractViolationKind.MISSING_INTERFACE_METHOD)
                .orElseThrow().message()).contains("testACLsOnKafkaTopic");
    }

    @Test
    void missingInterfaceMethod_passesWhenImplProvidesIt() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Svc.java", """
                package p;
                public interface Svc {
                    void doIt();
                }
                """);
        files.put("p/SvcImpl.java", """
                package p;
                public class SvcImpl implements Svc {
                    @Override public void doIt() {}
                }
                """);
        assertThat(validator.kinds(validator.validate(files)))
                .doesNotContain(ContractViolationKind.MISSING_INTERFACE_METHOD);
    }

    // ── Unknown method call on intra-project type ──────────────────────────

    /**
     * Real failure: {@code OrderEventListener} called
     * {@code pubsubService.getOrCreateTopic(...)} after the migrator
     * removed that method from {@code PubsubService} — javac:
     * "cannot find symbol method getOrCreateTopic".
     */
    @Test
    void unknownMethodCall_detectsCallerStillUsingOldName() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/PubsubService.java", """
                package p;
                public interface PubsubService {
                    void produce(String topic, String value);
                }
                """);
        files.put("p/Caller.java", """
                package p;
                public class Caller {
                    PubsubService pubsubService;
                    void run() { pubsubService.getOrCreateTopic("foo"); }
                }
                """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(ContractViolationKind.UNKNOWN_METHOD_CALL);
        assertThat(validator.firstByKind(v, ContractViolationKind.UNKNOWN_METHOD_CALL)
                .orElseThrow().message()).contains("getOrCreateTopic");
    }

    @Test
    void unknownMethodCall_passesWhenMethodIsKnown() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Svc.java", "package p;\npublic interface Svc { void run(); }");
        files.put("p/Caller.java",
                "package p;\npublic class Caller { Svc svc; void go() { svc.run(); } }");
        assertThat(validator.kinds(validator.validate(files)))
                .doesNotContain(ContractViolationKind.UNKNOWN_METHOD_CALL);
    }

    // ── Bad constructor arity ──────────────────────────────────────────────

    /**
     * Real failure: {@code AltrixPubsubMessage(Map<String,String>, String)}
     * was called with three arguments — javac:
     * "constructor AltrixPubsubMessage cannot be applied to given types".
     */
    @Test
    void badConstructorArity_detectsExtraArgument() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Msg.java", """
                package p;
                public class Msg {
                    public Msg(java.util.Map<String,String> a, String b) {}
                }
                """);
        files.put("p/Caller.java", """
                package p;
                public class Caller {
                    void run() { new Msg(null, "x", null); }
                }
                """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(ContractViolationKind.BAD_CONSTRUCTOR_ARITY);
    }

    @Test
    void badConstructorArity_acceptsAnyDeclaredArity() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Msg.java", """
                package p;
                public class Msg {
                    public Msg() {}
                    public Msg(String a) {}
                }
                """);
        files.put("p/Caller.java", "package p;\npublic class Caller { void r() { new Msg(\"x\"); new Msg(); } }");
        assertThat(validator.kinds(validator.validate(files)))
                .doesNotContain(ContractViolationKind.BAD_CONSTRUCTOR_ARITY);
    }

    // ── Invalid @Override ───────────────────────────────────────────────────

    /**
     * Real failure: {@code @Override} on a method whose parent interface
     * no longer declares it — javac:
     * "method does not override or implement a method from a supertype".
     */
    @Test
    void invalidOverride_detectsOrphanOverride() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/I.java", "package p;\npublic interface I { void newName(); }");
        files.put("p/Impl.java", """
                package p;
                public class Impl implements I {
                    @Override public void oldName() {}
                    @Override public void newName() {}
                }
                """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(ContractViolationKind.INVALID_OVERRIDE);
        assertThat(validator.firstByKind(v, ContractViolationKind.INVALID_OVERRIDE)
                .orElseThrow().symbol()).contains("oldName");
    }

    @Test
    void invalidOverride_passesWhenParentDeclaresMethod() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/I.java", "package p;\npublic interface I { void run(); }");
        files.put("p/Impl.java", """
                package p;
                public class Impl implements I {
                    @Override public void run() {}
                }
                """);
        assertThat(validator.kinds(validator.validate(files)))
                .doesNotContain(ContractViolationKind.INVALID_OVERRIDE);
    }

    // ── Non-public type imported cross-package ──────────────────────────────

    /**
     * Real failure: {@code AltrixKafkaMessage} declared without {@code public}
     * but imported from another package — javac:
     * "AltrixKafkaMessage is not public in com.example.altrix.pubsub;
     *  cannot be accessed from outside package".
     */
    @Test
    void nonPublicCrossPackage_detectsPackagePrivateLeak() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Msg.java",
                "package com.x.pubsub;\nclass AltrixKafkaMessage {}");
        files.put("p/Caller.java", """
                package com.x.order;
                import com.x.pubsub.AltrixKafkaMessage;
                public class Caller {}
                """);
        var v = validator.validate(files);
        assertThat(validator.kinds(v)).contains(ContractViolationKind.NON_PUBLIC_TYPE_USED_CROSS_PACKAGE);
    }

    @Test
    void nonPublicCrossPackage_allowsSamePackageUse() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Helper.java", "package com.x.pubsub;\nclass Helper {}");
        files.put("p/Use.java",
                "package com.x.pubsub;\nimport com.x.pubsub.Helper;\npublic class Use {}");
        // Same package — package-private is fine.
        assertThat(validator.kinds(validator.validate(files)))
                .doesNotContain(ContractViolationKind.NON_PUBLIC_TYPE_USED_CROSS_PACKAGE);
    }

    // ── Lombok credit ───────────────────────────────────────────────────────

    /**
     * Lombok @Data / @Getter / @Setter / @Builder should not cause false-
     * positive UNKNOWN_METHOD_CALL on the generated accessors.  We don't
     * have the Lombok-processed class, just the annotated source.
     */
    @Test
    void lombokAnnotations_creditGeneratedAccessors() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Order.java", """
                package p;
                @lombok.Data
                @lombok.Builder
                public class Order {
                    private String id;
                    private int qty;
                }
                """);
        files.put("p/Caller.java", """
                package p;
                public class Caller {
                    Order order;
                    void go() {
                        order.getId();
                        order.setQty(1);
                        Order.builder();
                    }
                }
                """);
        // Builder() is a static call so checkMethodCalls scope is `Order` —
        // we expect at least getId/setQty to NOT be flagged.
        var v = validator.validate(files);
        // The MethodCallExpr check fires on NameExpr scopes — `order.getId()`
        // and `order.setQty()` go through it.  Neither should appear in
        // UNKNOWN_METHOD_CALL because Lombok credit added them.
        boolean hasGetterFlagged = v.stream().anyMatch(x ->
                x.kind() == ContractViolationKind.UNKNOWN_METHOD_CALL
                        && x.message().contains("getId"));
        boolean hasSetterFlagged = v.stream().anyMatch(x ->
                x.kind() == ContractViolationKind.UNKNOWN_METHOD_CALL
                        && x.message().contains("setQty"));
        assertThat(hasGetterFlagged).isFalse();
        assertThat(hasSetterFlagged).isFalse();
    }

    // ── Smoke: clean project produces no violations ─────────────────────────

    @Test
    void cleanProject_producesNoViolations() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Iface.java", "package p;\npublic interface Iface { void run(); }");
        files.put("p/Impl.java", """
                package p;
                public class Impl implements Iface {
                    @Override public void run() {}
                }
                """);
        files.put("p/User.java", """
                package p;
                public class User {
                    Impl impl;
                    void use() { impl.run(); new Impl(); }
                }
                """);
        assertThat(validator.validate(files)).isEmpty();
        assertThat(validator.isClean(files)).isTrue();
    }

    @Test
    void nullOrEmptyInput_returnsEmpty() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(Map.of())).isEmpty();
        assertThat(validator.isClean(Map.of())).isTrue();
    }

    @Test
    void renderProducesOneLinePerViolation() {
        Map<String, String> files = Map.of(
                "Bad.java", "package p;\npublic class Wrong {}");
        var v = validator.validate(files);
        String rendered = validator.render(v);
        assertThat(rendered).contains("FILE_CLASS_MISMATCH");
        // One trailing newline per violation.
        assertThat(rendered.split("\n")).hasSize(v.size());
    }

    @Test
    void groupByFile_keysByPath() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Foo.java", "package p;\npublic class Wrong {}");
        files.put("p/Bar.java", "package p;\npublic class AlsoWrong {}");
        var v = validator.validate(files);
        Map<String, List<ContractViolation>> grouped = validator.groupByFile(v);
        assertThat(grouped.keySet()).containsExactlyInAnyOrder("p/Foo.java", "p/Bar.java");
    }

    @Test
    void kindsReflectsAllPresentKinds() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Bad.java", "package p;\npublic class Other {}"); // mismatch
        Set<ContractViolationKind> kinds = validator.kinds(validator.validate(files));
        assertThat(kinds).contains(ContractViolationKind.FILE_CLASS_MISMATCH);
    }
}
