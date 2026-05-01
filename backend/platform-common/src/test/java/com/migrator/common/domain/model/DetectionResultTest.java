package com.migrator.common.domain.model;

import com.migrator.common.domain.enums.BuildSystem;
import com.migrator.common.domain.enums.ConfigFormat;
import com.migrator.common.domain.enums.DetectedFramework;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionResultTest {

    @Test
    void builder_createsWithAllFields() {
        DetectionResult result = DetectionResult.builder()
                .buildSystem(BuildSystem.GRADLE_KOTLIN)
                .configFormat(ConfigFormat.YAML)
                .framework(DetectedFramework.SPRING_BOOT)
                .modules(List.of(":"))
                .build();

        assertThat(result.buildSystem()).isEqualTo(BuildSystem.GRADLE_KOTLIN);
        assertThat(result.configFormat()).isEqualTo(ConfigFormat.YAML);
        assertThat(result.framework()).isEqualTo(DetectedFramework.SPRING_BOOT);
        assertThat(result.modules()).containsExactly(":");
    }

    @Test
    void isMultiModule_trueWhenMultipleModules() {
        DetectionResult multi = DetectionResult.builder()
                .buildSystem(BuildSystem.GRADLE_KOTLIN)
                .configFormat(ConfigFormat.YAML)
                .framework(DetectedFramework.SPRING_BOOT)
                .modules(List.of(":api", ":service", ":domain"))
                .build();

        assertThat(multi.isMultiModule()).isTrue();
    }

    @Test
    void isMultiModule_falseWhenSingleModule() {
        DetectionResult single = DetectionResult.builder()
                .buildSystem(BuildSystem.MAVEN)
                .configFormat(ConfigFormat.PROPERTIES)
                .framework(DetectedFramework.JAKARTA_EE)
                .modules(List.of(":"))
                .build();

        assertThat(single.isMultiModule()).isFalse();
    }

    @Test
    void modules_areImmutableCopy() {
        List<String> mutable = new ArrayList<>(List.of(":api"));
        DetectionResult result = DetectionResult.builder()
                .buildSystem(BuildSystem.MAVEN)
                .configFormat(ConfigFormat.YAML)
                .framework(DetectedFramework.SPRING_BOOT)
                .modules(mutable)
                .build();

        mutable.add(":extra");

        assertThat(result.modules()).hasSize(1);
    }
}
