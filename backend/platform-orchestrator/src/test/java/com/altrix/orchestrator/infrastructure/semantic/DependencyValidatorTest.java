package com.altrix.orchestrator.infrastructure.semantic;

import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Category;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.ClassDependency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyValidatorTest {

    private KafkaMigrationKnowledgeBase kb() {
        return new KafkaMigrationKnowledgeBase(List.of(),
                List.of(
                        new ClassDependency("org.apache.kafka.clients.consumer.*", "org.apache.kafka:kafka-clients", false),
                        new ClassDependency("org.apache.kafka.clients.producer.*", "org.apache.kafka:kafka-clients", false)),
                List.of(), List.of());
    }

    private DependencyValidator validator() {
        return new DependencyValidator(kb());
    }

    private static final String POM_WITHOUT_KAFKA = """
            <project>
              <dependencies>
                <dependency><groupId>jakarta.platform</groupId>
                  <artifactId>jakarta.jakartaee-api</artifactId></dependency>
              </dependencies>
            </project>""";

    private static final String POM_WITH_KAFKA = """
            <project>
              <dependencies>
                <dependency><groupId>org.apache.kafka</groupId>
                  <artifactId>kafka-clients</artifactId><version>3.9.0</version></dependency>
              </dependencies>
            </project>""";

    @Test
    void flagsMissingKafkaDependency() {
        var findings = validator().validate(Map.of(
                "p/S.java",
                "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class S {}"),
                POM_WITHOUT_KAFKA);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).category()).isEqualTo(Category.MISSING_DEPENDENCY);
        assertThat(findings.get(0).symbol()).isEqualTo("org.apache.kafka:kafka-clients");
        assertThat(findings.get(0).message()).contains("KafkaConsumer");
    }

    @Test
    void noFindingWhenDependencyPresent() {
        var findings = validator().validate(Map.of(
                "p/S.java",
                "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class S {}"),
                POM_WITH_KAFKA);
        assertThat(findings).isEmpty();
    }

    @Test
    void dedupesMultipleImportsOfTheSameMissingCoordinate() {
        // Two different kafka-clients classes → still one MissingDependency finding.
        var findings = validator().validate(Map.of(
                "p/A.java", "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class A {}",
                "p/B.java", "package p;\nimport org.apache.kafka.clients.producer.KafkaProducer;\npublic class B {}"),
                POM_WITHOUT_KAFKA);
        assertThat(findings).hasSize(1);
    }

    @Test
    void ignoresImportsNotInTheKnowledgeBase() {
        var findings = validator().validate(Map.of(
                "p/S.java", "package p;\nimport java.util.List;\npublic class S {}"),
                POM_WITHOUT_KAFKA);
        assertThat(findings).isEmpty();
    }

    @Test
    void noPomMeansNoCheck() {
        var findings = validator().validate(Map.of(
                "p/S.java", "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class S {}"),
                null);
        assertThat(findings).isEmpty();
    }

    @Test
    void emptyInputsAreSafe() {
        assertThat(validator().validate(null, POM_WITH_KAFKA)).isEmpty();
        assertThat(validator().validate(Map.of(), POM_WITH_KAFKA)).isEmpty();
    }

    // ── Finding 5 regression: a hybridOnly mapping must never raise a
    // MISSING_DEPENDENCY finding on the unrelated default (non-hybrid) path,
    // even though PomDependencyReconciler's unconditional auto-add — a
    // SEPARATE consumer of the same knowledge-base entry — is unaffected.

    private KafkaMigrationKnowledgeBase kbWithHybridOnlySpringContext() {
        return new KafkaMigrationKnowledgeBase(List.of(),
                List.of(new ClassDependency("org.springframework.context.*",
                        "org.springframework:spring-context", true)),
                List.of(), List.of());
    }

    private static final String JAVA_USING_APPLICATION_CONTEXT_AWARE =
            "package p;\nimport org.springframework.context.ApplicationContextAware;\n"
                    + "public class S implements ApplicationContextAware {\n"
                    + "    public void setApplicationContext(org.springframework.context.ApplicationContext c) {}\n}";

    @Test
    void hybridOnlyMapping_noFinding_onOrdinarySpringBootPath_evenWithoutExplicitPomDeclaration() {
        var validator = new DependencyValidator(kbWithHybridOnlySpringContext());

        // Ordinary (non-hybrid) Spring Boot project — spring-context arrives
        // transitively via spring-boot-starter, never declared explicitly.
        var findings = validator.validate(Map.of("p/S.java", JAVA_USING_APPLICATION_CONTEXT_AWARE),
                POM_WITHOUT_KAFKA, JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);

        assertThat(findings).isEmpty();
    }

    @Test
    void hybridOnlyMapping_2ArgOverload_alsoDefaultsToNoFinding() {
        var validator = new DependencyValidator(kbWithHybridOnlySpringContext());

        var findings = validator.validate(Map.of("p/S.java", JAVA_USING_APPLICATION_CONTEXT_AWARE),
                POM_WITHOUT_KAFKA);

        assertThat(findings).isEmpty();
    }

    @Test
    void hybridOnlyMapping_flagsMissingDependency_whenTargetIsHybrid() {
        var validator = new DependencyValidator(kbWithHybridOnlySpringContext());

        var findings = validator.validate(Map.of("p/S.java", JAVA_USING_APPLICATION_CONTEXT_AWARE),
                POM_WITHOUT_KAFKA, JakartaMessagingTarget.SPRING_KAFKA_HYBRID);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).symbol()).isEqualTo("org.springframework:spring-context");
    }

    @Test
    void hybridOnlyMapping_noFinding_whenHybridAndPomDeclaresItExplicitly() {
        var validator = new DependencyValidator(kbWithHybridOnlySpringContext());
        String pomWithSpringContext = """
                <project>
                  <dependencies>
                    <dependency><groupId>org.springframework</groupId>
                      <artifactId>spring-context</artifactId></dependency>
                  </dependencies>
                </project>""";

        var findings = validator.validate(Map.of("p/S.java", JAVA_USING_APPLICATION_CONTEXT_AWARE),
                pomWithSpringContext, JakartaMessagingTarget.SPRING_KAFKA_HYBRID);

        assertThat(findings).isEmpty();
    }
}
