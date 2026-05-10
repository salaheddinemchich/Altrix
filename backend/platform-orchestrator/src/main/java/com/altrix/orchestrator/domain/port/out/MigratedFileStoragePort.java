package com.altrix.orchestrator.domain.port.out;

import com.altrix.common.domain.model.MigratedFile;

import java.util.List;

/**
 * Secondary port — writes migrated files to storage.
 * The domain never imports MinIO.
 */
public interface MigratedFileStoragePort {

    /**
     * Packs all migrated files into a ZIP and stores it.
     *
     * @param jobId         used to construct the storage key
     * @param migratedFiles all files produced by the migration
     * @return the storage key of the output ZIP
     */
    String storeMigratedZip(String jobId, List<MigratedFile> migratedFiles);
}
