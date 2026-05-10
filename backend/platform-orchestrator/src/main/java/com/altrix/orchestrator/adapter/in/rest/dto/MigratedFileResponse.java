package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;

/**
 * REST response DTO for a single migrated file (#119).
 * The {@code content} field contains the full rewritten source.
 */
public record MigratedFileResponse(
        String originalPath,
        String newPath,
        FileChangeType changeType,
        String diffSummary,
        String content
) {
    public static MigratedFileResponse from(MigratedFile f) {
        return new MigratedFileResponse(
                f.originalPath(),
                f.newPath(),
                f.changeType(),
                f.diffSummary(),
                f.content());
    }
}
