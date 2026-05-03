package com.altrix.project.domain.port.in;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.project.domain.model.Project;

import java.io.InputStream;

/**
 * Primary port — driving side.
 *
 * <p>Defines the single entry point for uploading a project ZIP.
 * The REST adapter calls this port; the domain service implements it.
 *
 * <p>Following the Interface Segregation Principle, this port declares
 * only one method — the upload operation. Nothing else.
 */
public interface UploadProjectUseCase {

    /**
     * Accepts a ZIP file upload, stores it, runs detection, and registers
     * the project for migration.
     *
     * @param userId                  ID of the authenticated user
     * @param fileName                original filename from the upload (used as project name)
     * @param zipContent              raw bytes of the uploaded ZIP
     * @param fileSizeBytes           size in bytes — used for storage quota checks
     * @param configFormatPreference  user's preferred output config format
     * @return the newly created {@link Project} domain entity
     */
    Project upload(
            String userId,
            String fileName,
            InputStream zipContent,
            long fileSizeBytes,
            ConfigFormatPreference configFormatPreference
    );
}
