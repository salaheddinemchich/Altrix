package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.infrastructure.config.MigrationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PomSanitizerTest {

    private PomSanitizer sanitizer(List<String> allowed) {
        return new PomSanitizer(new MigrationConfig(new MigrationConfig.Pom(allowed)));
    }

    /**
     * Real production failure: the AI inserted a hibernate-entitymanager
     * dependency the original POM didn't have.  The sanitizer should drop
     * it (artifactId not in original and not on the allow-list) while
     * keeping the legitimately added kafka-clients.
     */
    @Test
    void removesHallucinatedDependencyButKeepsAllowedAddition() {
        String original = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>jakarta.platform</groupId>
                      <artifactId>jakarta.jakartaee-api</artifactId>
                      <version>10.0.0</version>
                    </dependency>
                  </dependencies>
                </project>""";
        String migrated = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>jakarta.platform</groupId>
                      <artifactId>jakarta.jakartaee-api</artifactId>
                      <version>10.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>3.7.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.hibernate</groupId>
                      <artifactId>hibernate-entitymanager</artifactId>
                      <version>6.4.4.Final</version>
                    </dependency>
                  </dependencies>
                </project>""";

        String result = sanitizer(List.of("kafka-clients", "spring-kafka"))
                .stripHallucinatedDependencies(original, migrated);

        assertThat(result).contains("kafka-clients");
        assertThat(result).contains("jakarta.jakartaee-api");
        assertThat(result).doesNotContain("hibernate-entitymanager");
    }

    @Test
    void preservesOriginalDependenciesNotInAllowList() {
        // Original had guava; allow-list only mentions Kafka.  Guava must
        // survive because it was in the original (not an addition).
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>32.1.3-jre</version>
                    </dependency>
                  </dependencies>
                </project>""";
        String result = sanitizer(List.of("kafka-clients"))
                .stripHallucinatedDependencies(pom, pom);
        assertThat(result).contains("guava");
    }

    @Test
    void returnsInputUnchangedWhenMigratedPomFailsToParse() {
        // Migrated is garbage XML — caller's structural gate will reject it;
        // we just hand it back so we don't mask the real problem.
        String original = "<project><dependencies/></project>";
        String broken = "<project><!-- bad -- comment -->";
        String result = sanitizer(List.of("kafka-clients"))
                .stripHallucinatedDependencies(original, broken);
        assertThat(result).isEqualTo(broken);
    }

    @Test
    void allowListIsConfigurable_notHardcoded() {
        // Sanity-check that the allow-list actually flows from config.
        // A consumer overriding the list with [spring-kafka] only should
        // strip kafka-clients (an unexpected addition for that consumer).
        String original = "<project><dependencies/></project>";
        String migrated = """
                <project><dependencies>
                  <dependency><groupId>org.apache.kafka</groupId>
                  <artifactId>kafka-clients</artifactId><version>3.7</version></dependency>
                </dependencies></project>""";
        String result = sanitizer(List.of("spring-kafka"))
                .stripHallucinatedDependencies(original, migrated);
        assertThat(result).doesNotContain("kafka-clients");
    }
}
