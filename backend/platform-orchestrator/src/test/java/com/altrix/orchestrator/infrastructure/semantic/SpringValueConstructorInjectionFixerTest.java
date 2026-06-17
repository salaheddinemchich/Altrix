package com.altrix.orchestrator.infrastructure.semantic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringValueConstructorInjectionFixerTest {

    private final SpringValueConstructorInjectionFixer fixer = new SpringValueConstructorInjectionFixer();

    private String one(String src) {
        return fixer.fix(Map.of("p/S.java", src)).fixedFiles().get("p/S.java");
    }

    @Test
    void movesValueFieldReadInConstructorToConstructorParam() {
        // The exact boot-NPE bug: @Value field used directly in the constructor.
        String src = """
                package p;
                import org.springframework.beans.factory.annotation.Value;
                public class OrderPublisher {
                    @Value("${spring.kafka.bootstrap-servers}")
                    private String bootstrapServers;
                    private final String cfg;
                    public OrderPublisher() {
                        this.cfg = "bootstrap.servers=" + bootstrapServers;
                    }
                }""";
        String out = one(src);
        // Field is gone; constructor now injects it as a @Value parameter.
        assertThat(out).doesNotContain("private String bootstrapServers;");
        assertThat(out).contains("public OrderPublisher(");
        assertThat(out).contains("@Value(\"${spring.kafka.bootstrap-servers}\")");
        assertThat(out).contains("String bootstrapServers");
        assertThat(out).contains("bootstrap.servers=\" + bootstrapServers"); // body usage intact
    }

    @Test
    void fixes_ValueField_read_in_helper_method_called_from_constructor() {
        // The exact bd4e0ba5 bug — field read in getProducerProps() invoked from
        // the constructor.  The OLD fixer missed this because the field is also
        // used outside the ctor.  Keep-and-assign converts it correctly.
        String src = """
                package p;
                import java.util.Properties;
                import org.springframework.beans.factory.annotation.Value;
                public class OrderPublisher {
                    @Value("${spring.kafka.bootstrap-servers}")
                    private String bootstrapServers;
                    private final Properties props;
                    public OrderPublisher() {
                        this.props = getProducerProps();
                    }
                    private Properties getProducerProps() {
                        Properties p = new Properties();
                        p.put("bootstrap.servers", bootstrapServers);
                        return p;
                    }
                }""";
        String out = one(src);
        // Field KEPT (helper still reads it) but now final, no @Value on the field.
        assertThat(out).contains("private final String bootstrapServers;");
        assertThat(out).doesNotContain("@Value(\"${spring.kafka.bootstrap-servers}\")\n    private");
        // Injected via constructor param + assigned first.
        assertThat(out).contains("public OrderPublisher(@Value(\"${spring.kafka.bootstrap-servers}\") String bootstrapServers)");
        assertThat(out).contains("this.bootstrapServers = bootstrapServers;");
        // Helper still references the field.
        assertThat(out).contains("p.put(\"bootstrap.servers\", bootstrapServers)");
    }

    @Test
    void fixes_ValueField_read_transitively_through_two_helper_methods() {
        // ctor → first() → second() reads the field.  BFS must reach it.
        String src = """
                package p;
                import org.springframework.beans.factory.annotation.Value;
                public class S {
                    @Value("${x}") private String x;
                    private String cached;
                    public S() { this.cached = first(); }
                    private String first() { return second(); }
                    private String second() { return "v=" + x; }
                }""";
        String out = one(src);
        assertThat(out).contains("private final String x;");
        assertThat(out).contains("public S(@Value(\"${x}\") String x)");
        assertThat(out).contains("this.x = x;");
        assertThat(out).contains("\"v=\" + x"); // second() still reads it
    }

    @Test
    void leavesValueFieldNotUsedDuringConstructionAlone() {
        // Field injection that the constructor (and its call graph) never touches
        // is correct Spring usage — must NOT be converted.
        String src = """
                package p;
                import org.springframework.beans.factory.annotation.Value;
                public class S {
                    @Value("${x}") private String x;
                    public S() { }
                    public String get() { return x; }
                }""";
        assertThat(one(src)).isEqualTo(src);
    }

    @Test
    void skipsWhenFieldHasInitializer() {
        // A @Value field with an initializer can't be made final-and-assigned
        // without a double-init compile error — leave it alone.
        String src = """
                package p;
                import org.springframework.beans.factory.annotation.Value;
                public class S {
                    @Value("${x}") private String x = "default";
                    private final String cfg;
                    public S() { this.cfg = describe(); }
                    private String describe() { return x; }
                }""";
        assertThat(one(src)).isEqualTo(src);
    }

    @Test
    void skipsWhenMultipleConstructors() {
        String src = """
                package p;
                import org.springframework.beans.factory.annotation.Value;
                public class S {
                    @Value("${x}") private String x;
                    public S() { System.out.println(x); }
                    public S(int y) { System.out.println(x + y); }
                }""";
        assertThat(one(src)).isEqualTo(src);
    }
}
