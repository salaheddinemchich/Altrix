package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.orchestrator.domain.model.ConfigFormatResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFormatResolverTest {

    @Test
    void keepOriginal_returnsDetectedFormat() {
        assertThat(ConfigFormatResolver.resolve(ConfigFormat.YAML, ConfigFormatPreference.KEEP_ORIGINAL))
                .isEqualTo(ConfigFormat.YAML);
        assertThat(ConfigFormatResolver.resolve(ConfigFormat.PROPERTIES, ConfigFormatPreference.KEEP_ORIGINAL))
                .isEqualTo(ConfigFormat.PROPERTIES);
    }

    @Test
    void forceYaml_alwaysReturnsYaml() {
        assertThat(ConfigFormatResolver.resolve(ConfigFormat.PROPERTIES, ConfigFormatPreference.FORCE_YAML))
                .isEqualTo(ConfigFormat.YAML);
        assertThat(ConfigFormatResolver.resolve(ConfigFormat.YAML, ConfigFormatPreference.FORCE_YAML))
                .isEqualTo(ConfigFormat.YAML);
    }

    @Test
    void forceProperties_alwaysReturnsProperties() {
        assertThat(ConfigFormatResolver.resolve(ConfigFormat.YAML, ConfigFormatPreference.FORCE_PROPERTIES))
                .isEqualTo(ConfigFormat.PROPERTIES);
    }

    @Test
    void nullPreference_returnsDetectedFormat() {
        assertThat(ConfigFormatResolver.resolve(ConfigFormat.YAML, null))
                .isEqualTo(ConfigFormat.YAML);
    }

    @Test
    void nullDetected_defaultsToYaml() {
        assertThat(ConfigFormatResolver.resolve(null, ConfigFormatPreference.KEEP_ORIGINAL))
                .isEqualTo(ConfigFormat.YAML);
    }
}
