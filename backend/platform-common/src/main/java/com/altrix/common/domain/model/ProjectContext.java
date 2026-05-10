package com.altrix.common.domain.model;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import lombok.Builder;
import lombok.With;

import java.util.List;

/**
 * Immutable context object flowing through the agent pipeline.
 * Each agent returns an enriched copy via @With generated methods.
 */
@Builder
@With
public record ProjectContext(

        String jobId,
        String projectId,

        /** MinIO storage key of the uploaded project ZIP. */
        String storageKey,

        ConfigFormatPreference configFormatPreference,

        DetectionResult detectionResult,

        List<String> pubSubTopics,
        List<String> pubSubSubscriptions,
        List<String> listenerClasses,
        List<String> publisherClasses,

        List<MigratedFile> migratedFiles,

        /**
         * When true, bypasses the ContextAnalyzer Redis cache for this run (#158).
         * Null is treated as false.
         */
        Boolean forceFresh

) {
    public ProjectContext {
        pubSubTopics        = pubSubTopics        != null ? List.copyOf(pubSubTopics)        : List.of();
        pubSubSubscriptions = pubSubSubscriptions != null ? List.copyOf(pubSubSubscriptions)  : List.of();
        listenerClasses     = listenerClasses     != null ? List.copyOf(listenerClasses)      : List.of();
        publisherClasses    = publisherClasses    != null ? List.copyOf(publisherClasses)     : List.of();
        migratedFiles       = migratedFiles       != null ? List.copyOf(migratedFiles)        : List.of();
    }
}
