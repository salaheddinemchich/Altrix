package com.altrix.common.domain.enums;

/**
 * User-selected messaging implementation for a Jakarta EE migration target.
 * Meaningful only when the detected source/target framework is Jakarta EE —
 * ignored for Spring Boot projects.
 *
 * <ul>
 *   <li>{@code NATIVE_KAFKA_CLIENTS} — raw {@code kafka-clients} + CDI/EJB lifecycle
 *       (default; today's only Jakarta EE behavior).</li>
 *   <li>{@code SPRING_KAFKA_HYBRID} — {@code spring-kafka} bridged manually into the
 *       CDI container via a hand-bootstrapped Spring {@code ApplicationContext}.
 *       Opt-in only; never inferred from source.</li>
 * </ul>
 */
public enum JakartaMessagingTarget {
    NATIVE_KAFKA_CLIENTS,
    SPRING_KAFKA_HYBRID
}
