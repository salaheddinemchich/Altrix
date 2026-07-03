package com.altrix.common.domain.model;

import com.altrix.common.domain.enums.JakartaMessagingTarget;

import java.io.Serializable;

import java.util.List;

/**
 * Output of {@code ContextAnalyzerAgent} (Agent 1).
 *
 * <p>A structured summary of what the analyzer found in the source project —
 * the components, integrations, and migration-relevant entities the planner
 * will use to decide what to migrate and how.
 */
public record AnalysisReport(

        String projectId,

        /** MinIO storage key of the uploaded ZIP — propagated to MigrationPlan for CoreMigratorAgent. */
        String storageKey,

        /** Detected source components (topics, queues, listeners, publishers, services, etc.). */
        List<String> detectedComponents,

        /** Detected external integrations (cloud providers, brokers, frameworks). */
        List<String> detectedIntegrations,

        /** Free-form summary the planner can include in its prompt. */
        String summary,

        /** Carried from {@code ProjectContext}; only meaningful for Jakarta EE sources. */
        JakartaMessagingTarget jakartaMessagingTarget

) implements Serializable {
    public AnalysisReport {
        storageKey = storageKey != null ? storageKey : "";
        detectedComponents = detectedComponents != null ? List.copyOf(detectedComponents) : List.of();
        detectedIntegrations = detectedIntegrations != null ? List.copyOf(detectedIntegrations) : List.of();
        summary = summary != null ? summary : "";
        jakartaMessagingTarget = jakartaMessagingTarget != null
                ? jakartaMessagingTarget : JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS;
    }

    public static AnalysisReport empty(String projectId) {
        return new AnalysisReport(projectId, "", List.of(), List.of(), "", JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
    }
}
