package com.altrix.common.domain.model;

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

        /** Detected source components (topics, queues, listeners, publishers, services, etc.). */
        List<String> detectedComponents,

        /** Detected external integrations (cloud providers, brokers, frameworks). */
        List<String> detectedIntegrations,

        /** Free-form summary the planner can include in its prompt. */
        String summary

) {
    public AnalysisReport {
        detectedComponents = detectedComponents != null ? List.copyOf(detectedComponents)   : List.of();
        detectedIntegrations = detectedIntegrations != null ? List.copyOf(detectedIntegrations) : List.of();
        summary = summary != null ? summary : "";
    }

    public static AnalysisReport empty(String projectId) {
        return new AnalysisReport(projectId, List.of(), List.of(), "");
    }
}
