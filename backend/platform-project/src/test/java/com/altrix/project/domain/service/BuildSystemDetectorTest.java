package com.altrix.project.domain.service;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSystemDetectorTest {

    BuildSystemDetector detector;
    Project baseProject;

    @BeforeEach
    void setUp() {
        detector = new BuildSystemDetector();
        baseProject = Project.create("user-1", "app.zip", "uploads/app.zip", null, null);
    }

    private InputStream zipWith(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                zos.write(bytes, 0, bytes.length);
                zos.closeEntry();
            }
        }
        return new ByteArrayInputStream(baos.toByteArray());
    }

    @Test
    void detect_withGradleKts_detectsGradleKotlin() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of("build.gradle.kts", "plugins { java }")));

        assertThat(result.getBuildSystem()).isEqualTo(BuildSystem.GRADLE_KOTLIN);
    }

    @Test
    void detect_withGradleGroovy_detectsGradleGroovy() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of("build.gradle", "apply plugin: 'java'")));

        assertThat(result.getBuildSystem()).isEqualTo(BuildSystem.GRADLE_GROOVY);
    }

    @Test
    void detect_withPomXml_detectsMaven() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of("pom.xml", "<project/>")));

        assertThat(result.getBuildSystem()).isEqualTo(BuildSystem.MAVEN);
    }

    @Test
    void detect_withApplicationYml_detectsYaml() throws IOException {
        Project result = detector.detect(baseProject,
                zipWith(Map.of("src/main/resources/application.yml", "spring:\n  app: test")));

        assertThat(result.getConfigFormat()).isEqualTo(ConfigFormat.YAML);
    }

    @Test
    void detect_withApplicationYamlExtension_detectsYaml() throws IOException {
        Project result = detector.detect(baseProject,
                zipWith(Map.of("src/main/resources/application.yaml", "spring:\n  app: test")));

        assertThat(result.getConfigFormat()).isEqualTo(ConfigFormat.YAML);
    }

    @Test
    void detect_withApplicationProperties_detectsProperties() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "pom.xml", "<project/>",
                "src/main/resources/application.properties", "spring.app=test"
        )));

        assertThat(result.getConfigFormat()).isEqualTo(ConfigFormat.PROPERTIES);
    }

    @Test
    void detect_withSpringBootImport_detectsSpringBoot() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "build.gradle.kts", "plugins { }",
                "src/main/java/App.java", "import org.springframework.boot.SpringApplication;"
        )));

        assertThat(result.getFramework()).isEqualTo(DetectedFramework.SPRING_BOOT);
    }

    @Test
    void detect_withSpringCoreOnly_detectsSpringFramework() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "build.gradle.kts", "plugins { }",
                "src/main/java/App.java", "import org.springframework.core.env.Environment;"
        )));

        assertThat(result.getFramework()).isEqualTo(DetectedFramework.SPRING_FRAMEWORK);
    }

    @Test
    void detect_withJakartaEjb_detectsJakartaEe() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "pom.xml", "<project/>",
                "src/main/java/Service.java", "import jakarta.ejb.Stateless;"
        )));

        assertThat(result.getFramework()).isEqualTo(DetectedFramework.JAKARTA_EE);
    }

    @Test
    void detect_withJavaxEjb_detectsJavaEe() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "pom.xml", "<project/>",
                "src/main/java/Service.java", "import javax.ejb.Stateless;"
        )));

        assertThat(result.getFramework()).isEqualTo(DetectedFramework.JAVA_EE);
    }

    @Test
    void detect_emptyZip_defaultsToGradleKotlinYamlSpringBoot() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of()));

        assertThat(result.getBuildSystem()).isEqualTo(BuildSystem.GRADLE_KOTLIN);
        assertThat(result.getConfigFormat()).isEqualTo(ConfigFormat.YAML);
        assertThat(result.getFramework()).isEqualTo(DetectedFramework.SPRING_BOOT);
    }

    @Test
    void detect_gradleKtsTakesPriorityOverGroovy() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "build.gradle.kts", "plugins { }",
                "build.gradle", "apply plugin: 'java'"
        )));

        assertThat(result.getBuildSystem()).isEqualTo(BuildSystem.GRADLE_KOTLIN);
    }

    @Test
    void detect_marksProjectReady_afterSuccessfulDetection() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of("build.gradle.kts", "plugins { java }")));

        assertThat(result.getStatus()).isEqualTo(ProjectStatus.READY);
    }

    @Test
    void detect_withKotlinSourceFile_scansFramework() throws IOException {
        Project result = detector.detect(baseProject, zipWith(Map.of(
                "build.gradle.kts", "plugins { }",
                "src/main/kotlin/App.kt", "import org.springframework.boot.SpringApplication"
        )));

        assertThat(result.getFramework()).isEqualTo(DetectedFramework.SPRING_BOOT);
    }
}
