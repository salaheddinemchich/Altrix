package com.altrix.project.domain.model;

/**
 * Provenance of a {@link Project} row — recorded for audit and to power the
 * webhook auto-trigger lookup (#90).
 *
 * <p>{@code MANUAL} covers legacy ZIP uploads from the original
 * {@code /upload} endpoint; {@code GIT_CLONE} is the operator-initiated
 * {@code /clone} call; {@code WEBHOOK} is reserved for re-clones started
 * automatically when a {@code migration.commit.detected} event fires.
 */
public enum ProjectSource {
    MANUAL,
    GIT_CLONE,
    WEBHOOK
}
