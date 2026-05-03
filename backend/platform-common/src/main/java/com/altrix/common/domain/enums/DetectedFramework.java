package com.altrix.common.domain.enums;

/**
 * The Java framework detected in the uploaded project.
 *
 * <p>Detection is based on scanning source file imports:
 * <ul>
 *   <li>{@code SPRING_BOOT}      — {@code org.springframework.boot.*} found</li>
 *   <li>{@code SPRING_FRAMEWORK} — {@code org.springframework.*} found but no Boot</li>
 *   <li>{@code JAKARTA_EE}       — {@code jakarta.ejb.*} or {@code jakarta.ws.rs.*} found</li>
 *   <li>{@code JAVA_EE}          — {@code javax.ejb.*} or {@code javax.ws.rs.*} found</li>
 * </ul>
 */
public enum DetectedFramework {
    SPRING_BOOT,
    SPRING_FRAMEWORK,
    JAKARTA_EE,
    JAVA_EE
}
