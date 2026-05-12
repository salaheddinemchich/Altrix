package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.service.PlanSimilarityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agent 2 — Migration Planner.
 *
 * <p>Consumes an {@link AnalysisReport} and generates a full {@link MigrationPlan}.
 * Before calling the AI, it checks the similarity cache (#155): if a plan with
 * Jaccard dependency-overlap ≥ the configured threshold exists for a project with
 * the same Spring Boot major version, that plan is returned immediately (marked
 * {@code CACHE_ASSISTED} in workflow state) and the AI call is skipped entirely.
 *
 * <p>Degrades gracefully: AI failures produce a minimal fallback plan so the
 * pipeline never blocks on a transient provider error.
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
              "summary": "2–3 sentence overview",
              "targetFiles": ["src/main/java/com/example/Listener.java", "..."]
            }

            targetFiles must list the exact relative file paths (as they appear in the ZIP) that contain
            Google Cloud Pub/Sub code and require rewriting. Omit files that have no Pub/Sub usage.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiPort aiPort;
    private final PlanSimilarityService planSimilarityService;

    @Override
    public String getName() {
        return "Migration Planner";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public MigrationPlan execute(AnalysisReport input) {
        if (input == null) throw new AgentFailureException(getName(), "input AnalysisReport was null");
        log.info("[{}] planning migration for project '{}'", getName(), input.projectId());

        // Similarity cache check — skip AI when a close-enough plan already exists
        Optional<MigrationPlan> cached = planSimilarityService.findSimilar(input);
        if (cached.isPresent()) {
            log.info("[{}] similarity cache HIT for project='{}' — skipping AI call",
                    getName(), input.projectId());
            return cached.get();
        }

        try {
            String response = aiPort.chatFast(SYSTEM_PROMPT, buildUserContent(input));
            MigrationPlan plan = parsePlan(input.projectId(), input.storageKey(), response);
            planSimilarityService.store(input, plan);
            return plan;
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
            JsonNode root = MAPPER.readTree(extractJson(response));
            String targetStack = root.path("targetStack").asText("Spring Boot 3 + Apache Kafka");
            List<String> steps = toStringList(root.path("steps"));
            String riskLevel = root.path("riskLevel").asText("MEDIUM");
            String estimatedEffort = root.path("estimatedEffort").asText("TBD");
            String summary = root.path("summary").asText("");
            List<String> targetFiles = toStringList(root.path("targetFiles"));
            return new MigrationPlan(projectId, storageKey, targetStack, steps,
                    riskLevel, estimatedEffort, summary, targetFiles);
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
                "Spring Boot 3 + Apache Kafka", List.of(), "MEDIUM", "TBD", summary, List.of());
    }

    private static String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        return (start >= 0 && end > start) ? response.substring(start, end + 1) : response;
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(n -> { if (!n.asText("").isBlank()) result.add(n.asText()); });
        return result;
    }
}
