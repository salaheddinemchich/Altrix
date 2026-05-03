package com.altrix.project.domain.model;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.DetectedFramework;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectTest {

    @Test
    void create_producesProjectInPendingStatus() {
        Project project = Project.create(
                "user-1", "myapp.zip", "uploads/myapp.zip",
                ConfigFormatPreference.KEEP_ORIGINAL);

        assertThat(project.getId()).isNotBlank();
        assertThat(project.getUserId()).isEqualTo("user-1");
        assertThat(project.getName()).isEqualTo("myapp.zip");
        assertThat(project.getStorageKey()).isEqualTo("uploads/myapp.zip");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PENDING);
        assertThat(project.getCreatedAt()).isNotNull();
    }

    @Test
    void create_defaultsConfigFormatPreference_whenNull() {
        Project project = Project.create("user-1", "app.zip", "key", null);

        assertThat(project.getConfigFormatPreference())
                .isEqualTo(ConfigFormatPreference.KEEP_ORIGINAL);
    }

    @Test
    void withDetectionApplied_movesToReadyStatus() {
        Project project = Project.create("user-1", "app.zip", "key", null);

        Project ready = project.withDetectionApplied(
                BuildSystem.GRADLE_KOTLIN,
                ConfigFormat.YAML,
                DetectedFramework.SPRING_BOOT);

        assertThat(ready.getStatus()).isEqualTo(ProjectStatus.READY);
        assertThat(ready.getBuildSystem()).isEqualTo(BuildSystem.GRADLE_KOTLIN);
        assertThat(ready.getConfigFormat()).isEqualTo(ConfigFormat.YAML);
        assertThat(ready.getFramework()).isEqualTo(DetectedFramework.SPRING_BOOT);
    }

    @Test
    void withDetectionApplied_isImmutable_originalUnchanged() {
        Project original = Project.create("user-1", "app.zip", "key", null);
        original.withDetectionApplied(
                BuildSystem.MAVEN, ConfigFormat.PROPERTIES, DetectedFramework.JAKARTA_EE);

        assertThat(original.getStatus()).isEqualTo(ProjectStatus.PENDING);
        assertThat(original.getBuildSystem()).isNull();
    }

    @Test
    void withError_setsErrorStatus() {
        Project project = Project.create("user-1", "app.zip", "key", null);
        Project errored = project.withError();

        assertThat(errored.getStatus()).isEqualTo(ProjectStatus.ERROR);
    }

    @Test
    void equality_basedOnIdOnly() {
        Project p = Project.create("user-1", "app.zip", "key", null);
        Project copy = p.withError(); // different status, same id

        assertThat(p).isEqualTo(copy);
        assertThat(p).hasSameHashCodeAs(copy);
    }
}
