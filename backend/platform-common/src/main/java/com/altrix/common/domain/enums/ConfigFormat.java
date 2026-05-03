package com.altrix.common.domain.enums;

/**
 * The configuration file format detected in the uploaded project.
 *
 * <p>The migrator preserves the original format by default.
 * This can be overridden per-job via {@link ConfigFormatPreference}.
 */
public enum ConfigFormat {

    /** {@code application.yml} or {@code application.yaml} */
    YAML,

    /** {@code application.properties} */
    PROPERTIES
}
