package com.migrator.project.domain.model;

/**
 * Lifecycle status of a {@link Project}.
 *
 * <pre>
 *   PENDING → READY   (detection succeeded)
 *   PENDING → ERROR   (detection failed)
 * </pre>
 */
public enum ProjectStatus {

    /** Project uploaded, detection not yet run. */
    PENDING,

    /** Detection complete — project is ready to be migrated. */
    READY,

    /** Detection failed — see error logs for details. */
    ERROR
}
