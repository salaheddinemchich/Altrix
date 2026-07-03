package com.altrix.orchestrator.infrastructure.hybrid;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdiLookupCallScannerTest {

    private final CdiLookupCallScanner scanner = new CdiLookupCallScanner();

    private CompilationUnit parse(String source) {
        return new JavaParser().parse(source).getResult().orElseThrow();
    }

    @Test
    void findsLiteralClassExprTarget() {
        var cu = parse("""
                package p;
                public class Listener {
                    public void handle() {
                        CdiLookup.get(OrderStore.class).markPaid("x");
                    }
                }""");

        var calls = scanner.scan(cu);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).literalTargetSimpleName()).isEqualTo("OrderStore");
        assertThat(calls.get(0).line()).isGreaterThan(0);
    }

    @Test
    void resolvesFullyQualifiedClassExprToSimpleName() {
        var cu = parse("""
                package p;
                public class Listener {
                    public void handle() {
                        CdiLookup.get(com.example.OrderStore.class);
                    }
                }""");

        var calls = scanner.scan(cu);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).literalTargetSimpleName()).isEqualTo("OrderStore");
    }

    @Test
    void nonLiteralArgument_hasNullLiteralTargetButIsStillReported() {
        var cu = parse("""
                package p;
                public class Listener {
                    public void handle(Class<?> target) {
                        CdiLookup.get(target);
                    }
                }""");

        var calls = scanner.scan(cu);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).literalTargetSimpleName()).isNull();
        assertThat(calls.get(0).argument().toString()).isEqualTo("target");
    }

    @Test
    void ignoresCallsNotNamedGet() {
        var cu = parse("""
                package p;
                public class Listener {
                    public void handle() {
                        CdiLookup.lookup(OrderStore.class);
                    }
                }""");

        assertThat(scanner.scan(cu)).isEmpty();
    }

    @Test
    void ignoresGetCallsOnADifferentScope() {
        var cu = parse("""
                package p;
                public class Listener {
                    public void handle(java.util.Map<String,String> m) {
                        m.get("key");
                    }
                }""");

        assertThat(scanner.scan(cu)).isEmpty();
    }

    @Test
    void ignoresNoArgGetCalls() {
        var cu = parse("""
                package p;
                public class Listener {
                    public void handle() {
                        CdiLookup.get();
                    }
                }""");

        assertThat(scanner.scan(cu)).isEmpty();
    }
}
