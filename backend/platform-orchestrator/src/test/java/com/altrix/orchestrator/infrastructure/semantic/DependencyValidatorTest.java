package com.altrix.orchestrator.infrastructure.semantic;

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
                        new ClassDependency("org.apache.kafka.clients.consumer.*", "org.apache.kafka:kafka-clients"),
                        new ClassDependency("org.apache.kafka.clients.producer.*", "org.apache.kafka:kafka-clients")),
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
}
