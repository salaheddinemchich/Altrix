package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.DetectedStack;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StackDetectorTest {

    private final StackDetector detector = new StackDetector();

    @Test
    void detectsJakartaMavenPayara() {
        String pom = """
                <project>
                  <properties><maven.compiler.source>17</maven.compiler.source></properties>
                  <dependencies>
                    <dependency><groupId>jakarta.platform</groupId>
                      <artifactId>jakarta.jakartaee-api</artifactId></dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <artifactId>payara-micro-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>""";
        DetectedStack s = detector.detect(Map.of("pom.xml", pom));
        assertThat(s.buildSystem()).isEqualTo("Maven");
        assertThat(s.framework()).isEqualTo("Jakarta EE");
        assertThat(s.runtime()).isEqualTo("Payara Micro");
        assertThat(s.languageVersion()).isEqualTo("17");
    }

    @Test
    void detectsSpringBootMaven() {
        String pom = """
                <project>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId></dependency>
                  </dependencies>
                </project>""";
        DetectedStack s = detector.detect(Map.of("pom.xml", pom));
        assertThat(s.framework()).isEqualTo("Spring Boot");
        assertThat(s.runtime()).isEqualTo("Embedded (Spring Boot)");
        assertThat(s.languageVersion()).isEqualTo("21");
    }

    @Test
    void detectsGradleKotlinDsl() {
        DetectedStack s = detector.detect(Map.of(
                "build.gradle.kts",
                "dependencies { implementation(\"org.springframework.boot:spring-boot-starter\") }\n"
                        + "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }"));
        assertThat(s.buildSystem()).isEqualTo("Gradle (Kotlin DSL)");
        assertThat(s.framework()).isEqualTo("Spring Boot");
    }

    @Test
    void detectsQuarkus() {
        DetectedStack s = detector.detect(Map.of("pom.xml",
                "<project><dependency><groupId>io.quarkus</groupId></dependency></project>"));
        assertThat(s.framework()).isEqualTo("Quarkus");
        assertThat(s.runtime()).isEqualTo("Quarkus");
    }

    @Test
    void unknownWhenNoBuildFile() {
        assertThat(detector.detect(Map.of())).isEqualTo(DetectedStack.unknown());
        assertThat(detector.detect(Map.of("README.md", "hi")).framework()).isEqualTo("unknown");
    }
}
