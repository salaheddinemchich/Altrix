package com.migrator.common.domain.enums;

/**
 * Describes what kind of change was applied to a file during migration.
 *
 * <p>Used in {@code MigratedFile} to communicate the nature of each change
 * to the user in the final migration report.
 */
public enum FileChangeType {

    /** File content was modified (most common — Java classes, config files). */
    MODIFIED,

    /** File did not exist in the source and was generated during migration. */
    CREATED,

    /** File existed in the source but is not needed after migration. */
    DELETED,

    /** File was copied as-is — no changes required. */
    UNCHANGED
}
