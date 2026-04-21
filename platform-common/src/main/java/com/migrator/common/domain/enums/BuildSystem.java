package com.migrator.common.domain.enums;

/**
 * The build system detected in the uploaded project root.
 *
 * <p>Detection priority order (first match wins):
 * <ol>
 *   <li>{@code GRADLE_KOTLIN} — {@code build.gradle.kts} present</li>
 *   <li>{@code GRADLE_GROOVY} — {@code build.gradle} present</li>
 *   <li>{@code MAVEN}         — {@code pom.xml} present</li>
 * </ol>
 */
public enum BuildSystem {
    GRADLE_KOTLIN,
    GRADLE_GROOVY,
    MAVEN
}
