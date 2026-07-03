package com.altrix.orchestrator.infrastructure.hybrid;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringKafkaTxGapDetectorTest {

    private final SpringKafkaTxGapDetector detector = new SpringKafkaTxGapDetector(new CdiLookupCallScanner());

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

    @Test
    void flagsApplicationScopedTarget_stillUnconverted() {
        String store = """
                package p;
                import jakarta.enterprise.context.ApplicationScoped;
                @ApplicationScoped
                public class OrderStore {
                    public void markPaid(String id) { }
                }""";

        var found = detector.detect(Map.of("p/OrderListener.java", LISTENER, "p/OrderStore.java", store));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).symbol()).isEqualTo("OrderStore");
        assertThat(found.get(0).message()).contains("still @ApplicationScoped");
    }

    @Test
    void doesNotFlag_whenTargetAlreadyStateless() {
        String store = """
                package p;
                import jakarta.ejb.Stateless;
                @Stateless
                public class OrderStore {
                    public void markPaid(String id) { }
                }""";

        var found = detector.detect(Map.of("p/OrderListener.java", LISTENER, "p/OrderStore.java", store));

        assertThat(found).isEmpty();
    }

    @Test
    void flagsDynamicCdiLookupTarget_cannotBeStaticallyResolved() {
        String dynamicListener = """
                package p;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Component;
                import com.example.config.CdiLookup;
                @Component
                public class OrderListener {
                    @KafkaListener(topics = "orders")
                    public void handle(String m) {
                        Class<?> target = resolveTargetClass();
                        CdiLookup.get(target).toString();
                    }
                    private Class<?> resolveTargetClass() { return Object.class; }
                }""";

        var found = detector.detect(Map.of("p/OrderListener.java", dynamicListener));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).contains("not a literal 'X.class'");
    }

    @Test
    void ignoresFilesWithoutKafkaListenerOrCdiLookup() {
        assertThat(detector.detect(Map.of("p/Plain.java", "package p; public class Plain {}"))).isEmpty();
        assertThat(detector.detect(Map.of())).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}
