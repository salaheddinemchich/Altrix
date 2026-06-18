package com.altrix.orchestrator.infrastructure.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyDiffAnalyzerTest {

    private final DependencyDiffAnalyzer analyzer = new DependencyDiffAnalyzer();

    @Test
    void detectsAddedAndRemovedDependencies() {
        String original = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.apis</groupId>
                      <artifactId>google-api-services-pubsub</artifactId>
                      <version>v1-rev20210208-1.31.0</version>
                    </dependency>
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
                      <version>3.7.1</version>
                    </dependency>
                  </dependencies>
                </project>""";

        DependencyDiffAnalyzer.Result result = analyzer.diff(original, migrated);

        assertThat(result.added()).containsExactly("kafka-clients");
        assertThat(result.removed()).containsExactly("google-api-services-pubsub");
    }

    @Test
    void returnsEmptyWhenNothingChanged() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>3.7.1</version>
                    </dependency>
                  </dependencies>
                </project>""";

        DependencyDiffAnalyzer.Result result = analyzer.diff(pom, pom);

        assertThat(result.added()).isEmpty();
        assertThat(result.removed()).isEmpty();
    }

    @Test
    void returnsEmptyWhenEitherSideIsBlank() {
        assertThat(analyzer.diff(null, "<project/>")).isEqualTo(DependencyDiffAnalyzer.Result.EMPTY);
        assertThat(analyzer.diff("<project/>", "")).isEqualTo(DependencyDiffAnalyzer.Result.EMPTY);
    }

    @Test
    void returnsEmptyWhenEitherSideFailsToParse() {
        String validPom = "<project><dependencies></dependencies></project>";
        String garbage = "not xml at all {{{";

        assertThat(analyzer.diff(garbage, validPom)).isEqualTo(DependencyDiffAnalyzer.Result.EMPTY);
        assertThat(analyzer.diff(validPom, garbage)).isEqualTo(DependencyDiffAnalyzer.Result.EMPTY);
    }
}
