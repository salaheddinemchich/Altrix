package com.altrix.orchestrator.infrastructure.semantic;

import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.Mapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicRepairEngineTest {

    /** A KB whose knownTypeImports covers the common consumer types + Duration. */
    private KafkaMigrationKnowledgeBase kbWithConsumerImports() {
        Mapping pull = new Mapping(
                "pubsub.pull", "consumer.poll(...)",
                List.of("org.apache.kafka.clients.consumer.KafkaConsumer"),   // required-classes
                List.of("org.apache.kafka.clients.consumer.KafkaConsumer",    // required-imports
                        "org.apache.kafka.clients.consumer.ConsumerRecord",
                        "java.time.Duration"),
                List.of("org.apache.kafka:kafka-clients"),
                List.of(),
                null);
        return new KafkaMigrationKnowledgeBase(List.of(pull), List.of(), List.of());
    }

    private KafkaMigrationKnowledgeBase kbWithForbidden(List<String> forbidden) {
        return new KafkaMigrationKnowledgeBase(List.of(), List.of(), forbidden);
    }

    private DeterministicRepairEngine engine(KafkaMigrationKnowledgeBase kb) {
        return new DeterministicRepairEngine(kb);
    }

    // ── remove invalid implements/extends Object (Lombok-cascade trigger) ───

    @Test
    void removesInvalidImplementsObject() {
        var e = engine(kbWithForbidden(List.of()));
        String src = "package p;\n@ApplicationScoped\npublic class GoogleErrorConverter implements Object {\n"
                + "    public RuntimeException convert(Throwable c) { return new RuntimeException(c); }\n}";
        var result = e.repair(Map.of("p/GoogleErrorConverter.java", src));

        String out = result.repairedFiles().get("p/GoogleErrorConverter.java");
        assertThat(out).doesNotContain("implements Object");
        assertThat(out).contains("public class GoogleErrorConverter {");
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.REMOVE_INVALID_OBJECT_SUPERTYPE);
    }

    @Test
    void doesNotTouchObjectUsedAsRealType() {
        var e = engine(kbWithForbidden(List.of()));
        // 'Object' as a param/field type must stay — only implements/extends clauses are removed.
        String src = "package p;\npublic class S {\n  void f(int x, Object y) {}\n  Object z;\n}";
        var result = e.repair(Map.of("p/S.java", src));
        assertThat(result.repairedFiles().get("p/S.java")).isEqualTo(src);
    }

    @Test
    void correctsMisplacedKafkaException() {
        var e = engine(kbWithForbidden(List.of()));
        String src = "package p;\nimport org.apache.kafka.common.errors.KafkaException;\n"
                + "public class S { void f() { throw new KafkaException(\"x\"); } }";
        var result = e.repair(Map.of("p/S.java", src));

        String out = result.repairedFiles().get("p/S.java");
        assertThat(out).contains("import org.apache.kafka.common.KafkaException;");
        assertThat(out).doesNotContain("common.errors.KafkaException");
    }

    // ── fix package declaration with path separators ────────────────────────

    @Test
    void fixesPackageDeclarationWithSlashes() {
        var e = engine(kbWithForbidden(List.of()));
        // The exact cluster-migration bug: package derived from the file path.
        String src = "package com.example/altrix/pubsub/tasks;\n"
                + "import com.example.altrix.pubsub.RetryTask;\n"
                + "public class AcknowledgeMessagesTask extends RetryTask<Void> {}";
        var result = e.repair(Map.of("p/AcknowledgeMessagesTask.java", src));

        String out = result.repairedFiles().get("p/AcknowledgeMessagesTask.java");
        assertThat(out).startsWith("package com.example.altrix.pubsub.tasks;");
        assertThat(out).doesNotContain("com.example/altrix");
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.FIX_PACKAGE_DECLARATION);
    }

    @Test
    void leavesValidPackageUntouched() {
        var e = engine(kbWithForbidden(List.of()));
        String src = "package p.q;\npublic class S {}";
        var result = e.repair(Map.of("p/S.java", src));
        assertThat(result.actions()).noneMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.FIX_PACKAGE_DECLARATION);
    }

    // ── correct hallucinated Kafka package prefixes ─────────────────────────

    @Test
    void correctsKafkaAdminPackageInImportAndInlineUsage() {
        var e = engine(kbWithForbidden(List.of()));
        String src = """
                package p;
                import org.apache.kafka.admin.NewTopic;
                public class S {
                    private final org.apache.kafka.admin.AdminClient adminClient = null;
                }""";
        var result = e.repair(Map.of("p/S.java", src));

        String out = result.repairedFiles().get("p/S.java");
        assertThat(out).doesNotContain("org.apache.kafka.admin.");
        assertThat(out).contains("import org.apache.kafka.clients.admin.NewTopic;");
        assertThat(out).contains("org.apache.kafka.clients.admin.AdminClient");
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.FIX_PACKAGE_PREFIX);
    }

    @Test
    void leavesCorrectKafkaClientsAdminUntouched() {
        var e = engine(kbWithForbidden(List.of()));
        String src = "package p;\nimport org.apache.kafka.clients.admin.AdminClient;\npublic class S {}";
        var result = e.repair(Map.of("p/S.java", src));
        assertThat(result.actions()).noneMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.FIX_PACKAGE_PREFIX);
    }

    // ── remove bogus java.lang imports ──────────────────────────────────────

    @Test
    void removesJavaLangTypeImportedFromWrongPackage() {
        var e = engine(kbWithForbidden(List.of()));
        String src = """
                package p;
                import org.apache.kafka.common.errors.RuntimeException;
                public class S { void go() { throw new RuntimeException("x"); } }""";
        var result = e.repair(Map.of("p/S.java", src));

        String out = result.repairedFiles().get("p/S.java");
        assertThat(out).doesNotContain("org.apache.kafka.common.errors.RuntimeException");
        assertThat(out).contains("throw new RuntimeException");   // usage untouched (resolves to java.lang)
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.REMOVE_BOGUS_JDK_IMPORT);
    }

    // ── remove leaked Google API imports (forbidden + unused) ───────────────

    /**
     * Real failure: the migrator rewrote the error handling but left
     * `com.google.api.client.*` imports behind, unused → "package does not
     * exist".  They are on the forbidden list and unused, so the engine drops
     * them — while keeping Guava (com.google.common.*), which is legitimate.
     */
    @Test
    void removesUnusedLeakedGoogleApiImportsButKeepsGuava() {
        var kb = kbWithForbidden(List.of(
                "com.google.api.client.*", "com.google.api.services.pubsub.*"));
        var e = engine(kb);
        String src = """
                package p;
                import com.google.api.client.googleapis.json.GoogleJsonResponseException;
                import com.google.api.client.http.HttpStatusCodes;
                import com.google.common.base.Preconditions;
                public class S {
                    void go() { Preconditions.checkArgument(true, "ok"); }
                }""";
        var result = e.repair(Map.of("p/S.java", src));

        String out = result.repairedFiles().get("p/S.java");
        assertThat(out).doesNotContain("com.google.api.client");           // leaked + unused → removed
        assertThat(out).contains("import com.google.common.base.Preconditions;"); // Guava, used → kept
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.REMOVE_FORBIDDEN_IMPORT);
    }

    // ── add missing imports ─────────────────────────────────────────────────

    /** KB whose knownTypeImports maps OffsetAndMetadata to its one canonical package. */
    private KafkaMigrationKnowledgeBase kbWithOffsetAndMetadata() {
        Mapping ack = new Mapping(
                "pubsub.ack", "consumer.commitSync(...)",
                List.of("org.apache.kafka.clients.consumer.OffsetAndMetadata"),  // required-classes
                List.of("org.apache.kafka.clients.consumer.KafkaConsumer"),      // required-imports
                List.of("org.apache.kafka:kafka-clients"),
                List.of(),
                null);
        return new KafkaMigrationKnowledgeBase(List.of(ack), List.of(), List.of());
    }

    // ── remove wrong-package imports of known types ─────────────────────────

    @Test
    void removesWrongPackageImportOfKnownTypeAndKeepsCanonical() {
        var e = engine(kbWithOffsetAndMetadata());
        // The exact bug from a real migration: hallucinated common.record import
        // sits alongside the correct clients.consumer one.
        String src = """
                package p;
                import org.apache.kafka.common.record.OffsetAndMetadata;
                import org.apache.kafka.clients.consumer.OffsetAndMetadata;
                public class S {
                    OffsetAndMetadata m;
                }""";
        var result = e.repair(Map.of("p/S.java", src));

        String out = result.repairedFiles().get("p/S.java");
        assertThat(out).doesNotContain("org.apache.kafka.common.record.OffsetAndMetadata");
        assertThat(out).contains("import org.apache.kafka.clients.consumer.OffsetAndMetadata;");
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.REMOVE_WRONG_PACKAGE_IMPORT);
    }

    @Test
    void leavesCorrectCanonicalImportUntouched() {
        var e = engine(kbWithOffsetAndMetadata());
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.OffsetAndMetadata;
                public class S { OffsetAndMetadata m; }""";
        var result = e.repair(Map.of("p/S.java", src));

        assertThat(result.actions()).noneMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.REMOVE_WRONG_PACKAGE_IMPORT);
    }

    @Test
    void addsMissingImportForUsedButNotImportedType() {
        var e = engine(kbWithConsumerImports());
        String src = """
                package p;
                public class S {
                    private final KafkaConsumer<String,String> c = null;
                }""";
        var result = e.repair(Map.of("p/S.java", src));

        String out = result.repairedFiles().get("p/S.java");
        assertThat(out).contains("import org.apache.kafka.clients.consumer.KafkaConsumer;");
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.ADD_MISSING_IMPORT);
    }

    @Test
    void addsDurationImportWhenUsed() {
        var e = engine(kbWithConsumerImports());
        String src = """
                package p;
                public class S {
                    void go() { var d = Duration.ofSeconds(5); }
                }""";
        String out = e.repair(Map.of("p/S.java", src)).repairedFiles().get("p/S.java");
        assertThat(out).contains("import java.time.Duration;");
    }

    @Test
    void doesNotDuplicateAnAlreadyPresentImport() {
        var e = engine(kbWithConsumerImports());
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.KafkaConsumer;
                public class S { KafkaConsumer<String,String> c; }""";
        var result = e.repair(Map.of("p/S.java", src));
        assertThat(result.changedAnything()).isFalse();
        // Exactly one import line for KafkaConsumer.
        assertThat(result.repairedFiles().get("p/S.java").split("import org.apache.kafka.clients.consumer.KafkaConsumer;", -1))
                .hasSize(2);
    }

    @Test
    void respectsWildcardImport() {
        var e = engine(kbWithConsumerImports());
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.*;
                public class S { KafkaConsumer<String,String> c; }""";
        assertThat(e.repair(Map.of("p/S.java", src)).changedAnything()).isFalse();
    }

    @Test
    void doesNotImportWhenTypeIsLocallyDeclared() {
        var e = engine(kbWithConsumerImports());
        // The project declares its OWN KafkaConsumer — don't shadow it with an import.
        String src = """
                package p;
                public class S {
                    KafkaConsumer c;
                    static class KafkaConsumer {}
                }""";
        assertThat(e.repair(Map.of("p/S.java", src)).changedAnything()).isFalse();
    }

    // ── remove forbidden + unused imports ───────────────────────────────────

    @Test
    void removesForbiddenImportWhenUnused() {
        var e = engine(kbWithForbidden(List.of(
                "org.apache.kafka.clients.consumer.OffsetCommitResult")));
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.OffsetCommitResult;
                public class S {}""";
        var result = e.repair(Map.of("p/S.java", src));
        assertThat(result.repairedFiles().get("p/S.java"))
                .doesNotContain("OffsetCommitResult");
        assertThat(result.actions()).anyMatch(a ->
                a.type() == DeterministicRepairEngine.RepairAction.Type.REMOVE_FORBIDDEN_IMPORT);
    }

    @Test
    void keepsForbiddenImportThatIsActuallyUsed() {
        // Conservative: if the forbidden type is referenced in the body,
        // don't remove the import (the AI tier handles the real fix).
        var e = engine(kbWithForbidden(List.of(
                "org.apache.kafka.clients.consumer.OffsetCommitResult")));
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.OffsetCommitResult;
                public class S { OffsetCommitResult r; }""";
        assertThat(e.repair(Map.of("p/S.java", src)).repairedFiles().get("p/S.java"))
                .contains("import org.apache.kafka.clients.consumer.OffsetCommitResult;");
    }

    @Test
    void forbiddenWildcardRemovesUnusedMatchingImport() {
        var e = engine(kbWithForbidden(List.of(
                "org.apache.kafka.common.security.auth.permission.*")));
        String src = """
                package p;
                import org.apache.kafka.common.security.auth.permission.AdminPermission;
                public class S {}""";
        assertThat(e.repair(Map.of("p/S.java", src)).repairedFiles().get("p/S.java"))
                .doesNotContain("AdminPermission");
    }

    // ── general ─────────────────────────────────────────────────────────────

    @Test
    void nonJavaFilesPassThroughUnchanged() {
        var e = engine(kbWithConsumerImports());
        var result = e.repair(Map.of("pom.xml", "<project/>"));
        assertThat(result.repairedFiles().get("pom.xml")).isEqualTo("<project/>");
        assertThat(result.changedAnything()).isFalse();
    }

    @Test
    void isIdempotent() {
        var e = engine(kbWithConsumerImports());
        String src = "package p;\npublic class S { KafkaConsumer<String,String> c; }";
        var once = e.repair(Map.of("p/S.java", src)).repairedFiles();
        var twice = e.repair(once);
        assertThat(twice.changedAnything()).isFalse();
        assertThat(twice.repairedFiles().get("p/S.java")).isEqualTo(once.get("p/S.java"));
    }

    @Test
    void emptyOrNullInputIsSafe() {
        var e = engine(kbWithConsumerImports());
        assertThat(e.repair(null).repairedFiles()).isEmpty();
        assertThat(e.repair(Map.of()).changedAnything()).isFalse();
    }
}
