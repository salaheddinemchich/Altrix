package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 2 — Migration Planner.
 *
 * <p>Consumes an {@link AnalysisReport} and generates a full {@link MigrationPlan}
 * by asking the fast AI model for a structured migration strategy — including the
 * target stack, ordered steps, risk level, and effort estimate.
 *
 * <p>Degrades gracefully: if the AI call fails, a minimal fallback plan is returned
 * so the pipeline continues without blocking on a transient provider error.
 */
@Slf4j
@Component("migrationPlannerAgent")
@RequiredArgsConstructor
public class MigrationPlannerAgent implements MigrationAgent<AnalysisReport, MigrationPlan> {

    private static final String SYSTEM_PROMPT = """
            You are an expert migration architect. Based on the analysis of a Java project, create a
            detailed migration plan from Google Cloud Pub/Sub to Apache Kafka (Spring Kafka).

            Return ONLY valid JSON — no markdown fences, no commentary:
            {
              "targetStack": "Spring Boot 3 + Apache Kafka",
              "steps": ["Step 1: ...", "Step 2: ...", "..."],
              "riskLevel": "LOW | MEDIUM | HIGH",
              "estimatedEffort": "e.g. 3–5 days",
              "summary": "2–3 sentence overview"
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiPort aiPort;

    @Override public String getName()  { return "Migration Planner"; }
    @Override public int getOrder() { return 2; }

    @Override
    public MigrationPlan execute(AnalysisReport input) {
        if (input == null) throw new AgentFailureException(getName(), "input AnalysisReport was null");
        log.info("[{}] planning migration for project '{}'", getName(), input.projectId());

        try {
            String response = aiPort.chatFast(SYSTEM_PROMPT, buildUserContent(input));
            return parsePlan(input.projectId(), input.storageKey(), response);
        } catch (Exception e) {
            log.warn("[{}] AI planning failed ({}), using fallback plan", getName(), e.getMessage());
            return fallbackPlan(input);
        }
    }

    private String buildUserContent(AnalysisReport report) {
        return "Project: %s%nComponents: %s%nIntegrations: %s%nSummary: %s".formatted(
                report.projectId(),
                String.join(", ", report.detectedComponents()),
                String.join(", ", report.detectedIntegrations()),
                report.summary());
    }

    private MigrationPlan parsePlan(String projectId, String storageKey, String response) {
        try {
            JsonNode root          = MAPPER.readTree(extractJson(response));
            String targetStack     = root.path("targetStack").asText("Spring Boot 3 + Apache Kafka");
            List<String> steps     = toStringList(root.path("steps"));
            String riskLevel       = root.path("riskLevel").asText("MEDIUM");
            String estimatedEffort = root.path("estimatedEffort").asText("TBD");
            String summary         = root.path("summary").asText("");
            return new MigrationPlan(projectId, storageKey, targetStack, steps, riskLevel, estimatedEffort, summary);
        } catch (Exception e) {
            log.warn("[{}] failed to parse AI response: {}", getName(), e.getMessage());
            throw new RuntimeException("Plan parse failed", e);
        }
    }

    private MigrationPlan fallbackPlan(AnalysisReport report) {
        String summary = report.summary().isBlank()
                ? "Migration plan — no steps generated (AI unavailable)"
                : "Migration plan based on: " + report.summary();
        return new MigrationPlan(report.projectId(), report.storageKey(),
                "Spring Boot 3 + Apache Kafka", List.of(), "MEDIUM", "TBD", summary);
    }

    private static String extractJson(String response) {
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        return (start >= 0 && end > start) ? response.substring(start, end + 1) : response;
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(n -> { if (!n.asText("").isBlank()) result.add(n.asText()); });
        return result;
    }
}
