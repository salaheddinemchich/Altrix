package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.ClassDependency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PomDependencyReconcilerTest {

    private PomDependencyReconciler reconciler(List<String> forbidden, List<ClassDependency> classDeps) {
        return new PomDependencyReconciler(
                new KafkaMigrationKnowledgeBase(List.of(), classDeps, List.of(), forbidden));
    }

    /**
     * Real production failure: CoreMigratorAgent's structural sanity check
     * reverted pom.xml to its pre-migration original (still on the GCP
     * Pub/Sub starter) while the Java files were correctly migrated to
     * Kafka APIs. The reconciler must close that gap deterministically.
     */
    @Test
    void removesForbiddenGcpDependencyAndAddsMissingKafkaDependency() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.cloud</groupId>
                      <artifactId>spring-cloud-gcp-starter-pubsub</artifactId>
                    </dependency>
                  </dependencies>
                </project>""";
        Map<String, String> javaFiles = Map.of(
                "p/Consumer.java",
                "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class Consumer {}");

        String result = reconciler(
                List.of("spring-cloud-gcp-starter-pubsub"),
                List.of(new ClassDependency("org.apache.kafka.clients.consumer.*", "org.apache.kafka:kafka-clients", false)))
                .reconcile(pom, javaFiles);

        assertThat(result).doesNotContain("spring-cloud-gcp-starter-pubsub");
        assertThat(result).contains("<groupId>org.apache.kafka</groupId>");
        assertThat(result).contains("<artifactId>kafka-clients</artifactId>");
        assertThat(result).contains("<version>3.9.0</version>");
    }

    // ── Regression: job 6da249e5-6b34-42f7-b98c-4969c0809340 — the migrated
    // SpringKafkaConfig.java legitimately imports org.apache.kafka.clients.admin.*
    // (for the KafkaAdmin/NewTopic beans), this reconciler correctly added the
    // missing kafka-clients dependency, but with no <version> — Maven refused
    // to even read the project model ("dependencies.dependency.version ...
    // is missing"), failing before compilation could run at all.

    @Test
    void addedDependencyAlwaysHasAVersion() {
        String pom = """
                <project>
                  <dependencies>
                  </dependencies>
                </project>""";
        Map<String, String> javaFiles = Map.of(
                "p/SpringKafkaConfig.java",
                "package p;\nimport org.apache.kafka.clients.admin.NewTopic;\npublic class SpringKafkaConfig {}");

        String result = reconciler(List.of(),
                List.of(new ClassDependency("org.apache.kafka.clients.admin.*", "org.apache.kafka:kafka-clients", false)))
                .reconcile(pom, javaFiles);

        assertThat(result).contains("<version>3.9.0</version>");
    }

    @Test
    void skipsAddingDependencyWithNoKnownFallbackVersion_ratherThanEmitInvalidXml() {
        String pom = """
                <project>
                  <dependencies>
                  </dependencies>
                </project>""";
        Map<String, String> javaFiles = Map.of(
                "p/Thing.java",
                "package p;\nimport com.unknown.lib.Thing;\npublic class Thing {}");

        String result = reconciler(List.of(),
                List.of(new ClassDependency("com.unknown.lib.*", "com.unknown:lib-no-known-version", false)))
                .reconcile(pom, javaFiles);

        assertThat(result).isEqualTo(pom);
    }

    @Test
    void doesNotDuplicateAlreadyPresentDependency() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>3.7.0</version>
                    </dependency>
                  </dependencies>
                </project>""";
        Map<String, String> javaFiles = Map.of(
                "p/Consumer.java",
                "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class Consumer {}");

        String result = reconciler(List.of(),
                List.of(new ClassDependency("org.apache.kafka.clients.consumer.*", "org.apache.kafka:kafka-clients", false)))
                .reconcile(pom, javaFiles);

        assertThat(result.split("kafka-clients", -1).length - 1).isEqualTo(1);
    }

    @Test
    void leavesCleanPomUntouched() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>3.7.0</version>
                    </dependency>
                  </dependencies>
                </project>""";

        String result = reconciler(List.of(), List.of()).reconcile(pom, Map.of());

        assertThat(result).isEqualTo(pom);
    }

    @Test
    void returnsInputUnchangedWhenPomDoesNotParse() {
        String broken = "<project><!-- bad -- comment -->";
        String result = reconciler(List.of("spring-cloud-gcp-starter-pubsub"), List.of())
                .reconcile(broken, Map.of());
        assertThat(result).isEqualTo(broken);
    }

    @Test
    void nullOrBlankPomIsSafe() {
        var r = reconciler(List.of(), List.of());
        assertThat(r.reconcile(null, Map.of())).isNull();
        assertThat(r.reconcile("", Map.of())).isEmpty();
    }
}
