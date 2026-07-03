package com.altrix.orchestrator.infrastructure.hybrid;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdiStatelessConverterTest {

    private final CdiStatelessConverter converter = new CdiStatelessConverter(new CdiLookupCallScanner());

    private static MigratedFile java(String path, String content) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.UNCHANGED).diffSummary("x")
                .build();
    }

    private static final String LISTENER = """
            package p;
            import org.springframework.kafka.annotation.KafkaListener;
            import org.springframework.stereotype.Component;
            import com.example.config.CdiLookup;
            @Component
            public class OrderListener {
                @KafkaListener(topics = "orders")
                public void handle(String m) {
                    CdiLookup.get(OrderStore.class).markPaid("x");
                }
            }""";

    private static final String APPLICATION_SCOPED_STORE = """
            package p;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class OrderStore {
                public void markPaid(String id) { }
                public String statusOf(String id) { return "PAID"; }
            }""";

    @Test
    void convertsReachedApplicationScopedClassToStateless() {
        MigratedFile listener = java("p/OrderListener.java", LISTENER);
        MigratedFile store = java("p/OrderStore.java", APPLICATION_SCOPED_STORE);

        List<MigratedFile> result = converter.convert(List.of(listener, store));

        MigratedFile converted = result.stream()
                .filter(f -> f.originalPath().equals("p/OrderStore.java")).findFirst().orElseThrow();
        assertThat(converted.content()).contains("@Stateless");
        assertThat(converted.content()).doesNotContain("@ApplicationScoped");
        assertThat(converted.content()).contains("import jakarta.ejb.Stateless;");
        assertThat(converted.content()).contains("import jakarta.ejb.TransactionAttribute;");
        assertThat(converted.content()).contains("import jakarta.ejb.TransactionAttributeType;");
        assertThat(converted.content()).contains(
                "@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)\n    public void markPaid");
        assertThat(converted.content()).contains(
                "@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)\n    public String statusOf");
        assertThat(converted.changeType()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(converted.diffSummary()).isEqualTo("Converted to @Stateless EJB — required for transaction "
                + "correctness when invoked from Spring Kafka consumer threads.");

        // The listener itself is untouched — only the reached CDI class changes.
        MigratedFile listenerOut = result.stream()
                .filter(f -> f.originalPath().equals("p/OrderListener.java")).findFirst().orElseThrow();
        assertThat(listenerOut).isSameAs(listener);
    }

    @Test
    void leavesApplicationScopedClassAlone_whenNotReachedViaCdiLookup() {
        MigratedFile unrelated = java("p/Unrelated.java", """
                package p;
                import jakarta.enterprise.context.ApplicationScoped;
                @ApplicationScoped
                public class Unrelated {
                    public void doThing() { }
                }""");

        List<MigratedFile> result = converter.convert(List.of(unrelated));

        assertThat(result).containsExactly(unrelated);
    }

    @Test
    void idempotent_alreadyStatelessClassLeftUntouched() {
        MigratedFile listener = java("p/OrderListener.java", LISTENER);
        MigratedFile alreadyStateless = java("p/OrderStore.java", """
                package p;
                import jakarta.ejb.Stateless;
                @Stateless
                public class OrderStore {
                    public void markPaid(String id) { }
                }""");

        List<MigratedFile> result = converter.convert(List.of(listener, alreadyStateless));

        MigratedFile out = result.stream()
                .filter(f -> f.originalPath().equals("p/OrderStore.java")).findFirst().orElseThrow();
        assertThat(out).isSameAs(alreadyStateless);
    }

    @Test
    void noOpWhenNoCdiLookupCallsAnywhere() {
        MigratedFile store = java("p/OrderStore.java", APPLICATION_SCOPED_STORE);
        List<MigratedFile> result = converter.convert(List.of(store));
        assertThat(result).containsExactly(store);
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(converter.convert(List.of())).isEmpty();
        assertThat(converter.convert(null)).isNull();
    }

    // ── Findings 1 & 4 regressions: AST-based mutation (not regex) ────────

    @Test
    void convertsSuccessfully_whenAnotherAnnotationSitsBetweenApplicationScopedAndClass() {
        MigratedFile listener = java("p/OrderListener.java", LISTENER);
        MigratedFile store = java("p/OrderStore.java", """
                package p;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Named;
                @ApplicationScoped
                @Named("orderStore")
                public class OrderStore {
                    public void markPaid(String id) { }
                }""");

        List<MigratedFile> result = converter.convert(List.of(listener, store));

        MigratedFile converted = result.stream()
                .filter(f -> f.originalPath().equals("p/OrderStore.java")).findFirst().orElseThrow();
        assertThat(converted.content()).contains("@Stateless");
        assertThat(converted.content()).doesNotContain("@ApplicationScoped");
        assertThat(converted.content()).contains("@Named(\"orderStore\")");
        assertThat(converted.content()).contains("@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)");
        assertThat(converted.changeType()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(converted.diffSummary()).isEqualTo("Converted to @Stateless EJB — required for transaction "
                + "correctness when invoked from Spring Kafka consumer threads.");
    }

    @Test
    void doesNotTouchCommentedOutMethodSignature_onlyTheRealMethodGetsTheAnnotation() {
        MigratedFile listener = java("p/OrderListener.java", LISTENER);
        String storeSource = """
                package p;
                import jakarta.enterprise.context.ApplicationScoped;
                @ApplicationScoped
                public class OrderStore {
                    /*
                    public void disabled() {
                        doSomethingDangerous();
                    }
                    */
                    public void markPaid(String id) { }
                }""";
        MigratedFile store = java("p/OrderStore.java", storeSource);

        List<MigratedFile> result = converter.convert(List.of(listener, store));

        MigratedFile converted = result.stream()
                .filter(f -> f.originalPath().equals("p/OrderStore.java")).findFirst().orElseThrow();
        // The comment text survives byte-for-byte — no annotation injected into it.
        String commentBlock = storeSource.substring(storeSource.indexOf("/*"), storeSource.indexOf("*/") + 2);
        assertThat(converted.content()).contains(commentBlock);
        assertThat(converted.content()).doesNotContain(
                "@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)\n    public void disabled");
        // Only the real method is annotated.
        long annotationCount = converted.content().split("@TransactionAttribute", -1).length - 1;
        assertThat(annotationCount).isEqualTo(1);
    }

    @Test
    void noConversion_whenReachedClassHasNoApplicationScopedAnnotation() {
        MigratedFile listener = java("p/OrderListener.java", LISTENER);
        // Reached via CdiLookup, but scoped some other way entirely (e.g. @Singleton) —
        // there is no @ApplicationScoped to convert, so this must NOT be reported as
        // MODIFIED with the EJB-conversion note (the bug this rewrite fixes).
        MigratedFile store = java("p/OrderStore.java", """
                package p;
                import jakarta.ejb.Singleton;
                @Singleton
                public class OrderStore {
                    public void markPaid(String id) { }
                }""");

        List<MigratedFile> result = converter.convert(List.of(listener, store));

        MigratedFile out = result.stream()
                .filter(f -> f.originalPath().equals("p/OrderStore.java")).findFirst().orElseThrow();
        assertThat(out).isSameAs(store);
        assertThat(out.changeType()).isEqualTo(FileChangeType.UNCHANGED);
    }
}
