package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.enums.FileChangeType;

/**
 * Before/after content for one migrated file (#119).
 *
 * <p>Powers the left/right panes of the Monaco diff viewer (#120) and the
 * unified-diff .patch export (#123).
 *
 * <p>{@code originalContent} is {@code null} for {@code CREATED} files
 * (nothing existed before) and {@code migratedContent} is {@code null} for
 * {@code DELETED} files (nothing remains after).
 */
public record FileDiffResponse(
        String originalPath,
        String newPath,
        FileChangeType changeType,
        String originalContent,
        String migratedContent,
        String diffSummary
) {}
