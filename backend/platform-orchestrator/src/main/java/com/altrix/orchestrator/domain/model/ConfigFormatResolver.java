package com.altrix.orchestrator.domain.model;

import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;

/**
 * Pure domain utility — resolves the effective output config format.
 *
 * <p>If the user chose KEEP_ORIGINAL, the detected format is used.
 * Otherwise the user's explicit preference wins.
 */
public final class ConfigFormatResolver {

    private ConfigFormatResolver() {}

    /**
     * @param detected   format found in the uploaded project
     * @param preference user's preference for the output
     * @return the format to use when writing migrated config files
     */
    public static ConfigFormat resolve(ConfigFormat detected, ConfigFormatPreference preference) {
        if (preference == null || preference == ConfigFormatPreference.KEEP_ORIGINAL) {
            return detected != null ? detected : ConfigFormat.YAML;
        }
        return switch (preference) {
            case FORCE_YAML       -> ConfigFormat.YAML;
            case FORCE_PROPERTIES -> ConfigFormat.PROPERTIES;
            default               -> detected != null ? detected : ConfigFormat.YAML;
        };
    }
}
