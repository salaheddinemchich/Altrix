package com.altrix.common.domain.enums;

/**
 * User preference for the output configuration file format after migration.
 *
 * <p>This is set per migration job and overrides the detected format:
 * <ul>
 *   <li>{@code KEEP_ORIGINAL}    — output stays in the same format as the source (default)</li>
 *   <li>{@code FORCE_YAML}       — convert {@code .properties} to {@code .yml} if needed</li>
 *   <li>{@code FORCE_PROPERTIES} — convert {@code .yml} to {@code .properties} if needed</li>
 * </ul>
 */
public enum ConfigFormatPreference {
    KEEP_ORIGINAL,
    FORCE_YAML,
    FORCE_PROPERTIES
}
