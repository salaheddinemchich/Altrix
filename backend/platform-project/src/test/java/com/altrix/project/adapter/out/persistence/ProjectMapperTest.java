package com.altrix.project.adapter.out.persistence;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectMapperTest {

    ProjectMapper mapper = new ProjectMapper();

    @Test
    void toDomain_mapsAllFields() {
        Instant now = Instant.now();
        ProjectJpaEntity entity = ProjectJpaEntity.builder()
                .id("proj-1")
                .userId("user-1")
                .name("myapp.zip")
                .storageKey("uploads/myapp.zip")
                .status(ProjectStatus.READY)
                .buildSystem(BuildSystem.GRADLE_KOTLIN)
                .configFormat(ConfigFormat.YAML)
                .framework(DetectedFramework.SPRING_BOOT)
                .configFormatPreference(ConfigFormatPreference.KEEP_ORIGINAL)
                .jakartaMessagingTarget(JakartaMessagingTarget.SPRING_KAFKA_HYBRID)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Project domain = mapper.toDomain(entity);

        assertThat(domain.getId()).isEqualTo("proj-1");
        assertThat(domain.getUserId()).isEqualTo("user-1");
        assertThat(domain.getName()).isEqualTo("myapp.zip");
        assertThat(domain.getStorageKey()).isEqualTo("uploads/myapp.zip");
        assertThat(domain.getStatus()).isEqualTo(ProjectStatus.READY);
        assertThat(domain.getBuildSystem()).isEqualTo(BuildSystem.GRADLE_KOTLIN);
        assertThat(domain.getConfigFormat()).isEqualTo(ConfigFormat.YAML);
        assertThat(domain.getFramework()).isEqualTo(DetectedFramework.SPRING_BOOT);
        assertThat(domain.getJakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
    }

    @Test
    void toJpaEntity_mapsAllFields() {
        Project domain = Project.create("user-1", "myapp.zip", "uploads/myapp.zip",
                ConfigFormatPreference.FORCE_PROPERTIES, JakartaMessagingTarget.SPRING_KAFKA_HYBRID);

        ProjectJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(domain.getId());
        assertThat(entity.getUserId()).isEqualTo("user-1");
        assertThat(entity.getName()).isEqualTo("myapp.zip");
        assertThat(entity.getStorageKey()).isEqualTo("uploads/myapp.zip");
        assertThat(entity.getStatus()).isEqualTo(ProjectStatus.PENDING);
        assertThat(entity.getConfigFormatPreference()).isEqualTo(ConfigFormatPreference.FORCE_PROPERTIES);
        assertThat(entity.getJakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
    }

    @Test
    void roundTrip_preservesAllFields() {
        Project original = Project.create("user-1", "app.zip", "key", null, null);
        ProjectJpaEntity entity = mapper.toJpaEntity(original);
        Project restored = mapper.toDomain(entity);

        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getUserId()).isEqualTo(original.getUserId());
        assertThat(restored.getName()).isEqualTo(original.getName());
        // #90 — MANUAL source is recorded for legacy ZIP uploads
        assertThat(restored.getSource()).isEqualTo(
                com.altrix.project.domain.model.ProjectSource.MANUAL);
        assertThat(restored.getRepoUrl()).isNull();
        assertThat(restored.getJakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
    }

    @Test
    void roundTrip_preservesGitProvenance() {
        Project original = Project.createFromGit(
                "user-1", "widgets", "k", null, JakartaMessagingTarget.SPRING_KAFKA_HYBRID,
                "https://github.com/acme/widgets.git",
                "main",
                com.altrix.project.domain.model.ProjectSource.GIT_CLONE);

        Project restored = mapper.toDomain(mapper.toJpaEntity(original));

        assertThat(restored.getRepoUrl()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(restored.getTrackedBranch()).isEqualTo("main");
        assertThat(restored.getSource()).isEqualTo(
                com.altrix.project.domain.model.ProjectSource.GIT_CLONE);
        assertThat(restored.getJakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
    }
}
