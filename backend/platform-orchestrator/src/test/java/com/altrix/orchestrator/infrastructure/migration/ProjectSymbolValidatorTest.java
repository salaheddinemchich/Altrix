package com.altrix.orchestrator.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSymbolValidatorTest {

    private final ProjectSymbolValidator validator = new ProjectSymbolValidator();

    /**
     * Reproduces the real production pattern: the AI rewrote
     * PaymentService to import KafkaService from com.example.altrix.pubsub
     * but never actually created a KafkaService class anywhere in the
     * artifact.  Sandbox compile then failed with "cannot find symbol".
     */
    @Test
    void flagsImportsForClassesNotDefinedAnywhereInTheArtifact() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/com/example/altrix/pubsub/PubsubService.java",
                "package com.example.altrix.pubsub;\npublic interface PubsubService {}");
        files.put("src/main/java/com/example/altrix/payment/PaymentService.java", """
                package com.example.altrix.payment;
                import com.example.altrix.pubsub.KafkaService;  // does NOT exist
                public class PaymentService { KafkaService svc; }
                """);

        Map<String, Set<String>> unresolved =
                validator.findUnresolvedImports("com.example.altrix", files);

        assertThat(unresolved).containsKey("src/main/java/com/example/altrix/payment/PaymentService.java");
        assertThat(unresolved.values().iterator().next())
                .containsExactly("com.example.altrix.pubsub.KafkaService");
    }

    @Test
    void resolvedImportsAreNotFlagged() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/X.java", "package a;\npublic class X {}");
        files.put("a/Y.java", "package a;\nimport a.X;\npublic class Y { X x; }");

        Map<String, Set<String>> unresolved = validator.findUnresolvedImports("a", files);

        assertThat(unresolved).isEmpty();
    }

    @Test
    void externalImportsAreNotChecked() {
        // org.apache.kafka.* might or might not exist — that's the retry
        // loop's job (it feeds compile errors back to the migrator).
        Map<String, String> files = Map.of(
                "X.java", """
                        package x;
                        import org.apache.kafka.common.AclScope;
                        public class X { AclScope s; }
                        """);

        Map<String, Set<String>> unresolved = validator.findUnresolvedImports("x", files);

        assertThat(unresolved).isEmpty();
    }

    @Test
    void wildcardImportsAreSkipped() {
        // Wildcards we cannot validate without the full classpath.
        Map<String, String> files = Map.of(
                "X.java", "package a;\nimport a.unknown.*;\npublic class X {}");
        assertThat(validator.findUnresolvedImports("a", files)).isEmpty();
    }

    @Test
    void inferBasePackage_returnsLongestCommonPrefix() {
        Map<String, String> files = Map.of(
                "A.java", "package com.example.altrix.order;\npublic class A {}",
                "B.java", "package com.example.altrix.payment;\npublic class B {}",
                "C.java", "package com.example.altrix;\npublic class C {}");
        assertThat(validator.inferBasePackage(files)).isEqualTo("com.example.altrix");
    }

    @Test
    void inferBasePackage_returnsEmptyForUnrelatedPackages() {
        Map<String, String> files = Map.of(
                "A.java", "package foo;\npublic class A {}",
                "B.java", "package bar;\npublic class B {}");
        assertThat(validator.inferBasePackage(files)).isEmpty();
    }

    @Test
    void indexCoversAllKindsOfTypeDeclarations() {
        Map<String, String> files = Map.of(
                "C.java", "package p;\npublic class C {}",
                "I.java", "package p;\npublic interface I {}",
                "E.java", "package p;\npublic enum E { A, B }",
                "R.java", "package p;\npublic record R(int x) {}",
                "Sealed.java", "package p;\npublic sealed class S permits Child {} final class Child extends S {}",
                "User.java", """
                        package p;
                        import p.C; import p.I; import p.E; import p.R; import p.S; import p.Child;
                        public class User {}
                        """);
        assertThat(validator.findUnresolvedImports("p", files)).isEmpty();
    }
}
