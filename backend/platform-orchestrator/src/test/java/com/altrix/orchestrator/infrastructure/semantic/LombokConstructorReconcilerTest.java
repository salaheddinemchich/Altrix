package com.altrix.orchestrator.infrastructure.semantic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LombokConstructorReconcilerTest {

    private final LombokConstructorReconciler reconciler = new LombokConstructorReconciler();

    private String one(String src) {
        return reconciler.reconcile(Map.of("p/S.java", src)).reconciledFiles().get("p/S.java");
    }

    @Test
    void dropsRequiredArgsConstructorWhenExplicitMatchingCtorExists() {
        // The real bug: @RequiredArgsConstructor + an explicit (String,String,int) ctor.
        String src = """
                package p;
                import lombok.Getter;
                import lombok.RequiredArgsConstructor;
                @Getter
                @RequiredArgsConstructor
                public class PubsubSubscription {
                    private final String topicName;
                    private final String groupId;
                    private final int ackTimeout;
                    public PubsubSubscription(String topicName, String groupId, int ackTimeout) {
                        this.topicName = topicName;
                        this.groupId = groupId;
                        this.ackTimeout = ackTimeout;
                    }
                }""";
        String out = one(src);
        assertThat(out).doesNotContain("@RequiredArgsConstructor");
        assertThat(out).contains("@Getter");                                  // other annotations kept
        assertThat(out).contains("public PubsubSubscription(String topicName"); // explicit ctor kept
    }

    @Test
    void keepsRequiredArgsConstructorWhenNoCollidingCtor() {
        // Explicit ctor has a DIFFERENT signature (1 arg) — no collision, keep Lombok.
        String src = """
                package p;
                import lombok.RequiredArgsConstructor;
                @RequiredArgsConstructor
                public class S {
                    private final String a;
                    private final String b;
                    public S(String only) { this.a = only; this.b = null; }
                }""";
        String out = one(src);
        assertThat(out).contains("@RequiredArgsConstructor");
    }

    @Test
    void dropsAllArgsConstructorOnFullFieldMatch() {
        String src = """
                package p;
                import lombok.AllArgsConstructor;
                @AllArgsConstructor
                public class S {
                    private String a;
                    private int b;
                    public S(String a, int b) { this.a = a; this.b = b; }
                }""";
        String out = one(src);
        assertThat(out).doesNotContain("@AllArgsConstructor");
        assertThat(out).contains("public S(String a, int b)");
    }

    @Test
    void leavesClassWithNoExplicitConstructorUntouched() {
        String src = """
                package p;
                import lombok.RequiredArgsConstructor;
                @RequiredArgsConstructor
                public class S { private final String a; }""";
        String out = one(src);
        assertThat(out).isEqualTo(src);
    }

    @Test
    void ignoresStaticFieldsWhenComputingRequiredArgs() {
        // Static final field must NOT count toward the generated ctor params.
        String src = """
                package p;
                import lombok.RequiredArgsConstructor;
                @RequiredArgsConstructor
                public class S {
                    public static final String K = "k";
                    private final String a;
                    public S(String a) { this.a = a; }
                }""";
        String out = one(src);
        assertThat(out).doesNotContain("@RequiredArgsConstructor");  // (String) matches the 1 final field
    }
}
