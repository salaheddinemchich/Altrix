package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.service.PlanSimilarityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
            You are an expert migration architect.  Based on the analysis of a Java project,
            create a detailed migration plan from Google Cloud Pub/Sub to Apache Kafka.

            The target project may use either or both of:

            (A) Modern Spring Cloud GCP — package org.springframework.cloud.gcp.pubsub.*
                Look for: @PubSubListener, @SubscriberHandler, PubSubTemplate,
                MessagePublisher, imports under google.cloud.pubsub.*.

            (B) Legacy GCP Pub/Sub REST v1 — package com.google.api.services.pubsub.*
                Look for: imports starting with com.google.api.services.pubsub,
                use of the Pubsub client, PubsubMessage / ReceivedMessage types,
                Pubsub.Projects.Topics.publish, Pubsub.Projects.Subscriptions.pull.
                User code often wraps these behind a "PubsubService" / "PubSubService" /
                "PubsubClient" class — include any file that depends on such wrappers.

            Both styles are equally valid migration targets and the plan must address
            whichever one the project actually uses (or both if mixed).

            Return ONLY valid JSON — no markdown fences, no commentary:
            {
              "targetStack": "Spring Boot 3 + Apache Kafka",  // or "Jakarta EE + Apache Kafka" if EJB project
              "steps": ["Step 1: ...", "Step 2: ...", "..."],
              "riskLevel": "LOW | MEDIUM | HIGH",
              "estimatedEffort": "e.g. 3–5 days",
              "summary": "2–3 sentence overview",
              "targetFiles": ["src/main/java/com/example/Listener.java", "..."]
            }

            targetFiles must list the exact relative file paths (as they appear in the ZIP)
            that contain Google Cloud Pub/Sub code — under EITHER style above — and
            therefore require rewriting.  Include user-defined wrapper classes
            (PubsubService, etc.) plus every class that depends on them.

            Crucially, ALSO include the surrounding build + config files that wire
            Pub/Sub into the project, otherwise the rewritten Java code will not
            compile or boot:
              - pom.xml / build.gradle / build.gradle.kts that declare any
                google-api-services-pubsub, google-cloud-pubsub, spring-cloud-gcp-pubsub
                or spring-cloud-gcp-starter-pubsub dependency.
              - application.yml / application.properties that set spring.cloud.gcp.pubsub.*,
                gcp.pubsub.*, GOOGLE_APPLICATION_CREDENTIALS, or PUBSUB_EMULATOR_HOST.
              - Java @Configuration / Jakarta EE config classes that wire Pubsub /
                PubSubTemplate / topic + subscription beans (e.g. PubsubConfig.java).

            Omit only files that have no Pub/Sub usage at all.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiPort aiPort;
    private final PlanSimilarityService planSimilarityService;
    private final FileReaderPort fileReader;

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
            plan = filterHallucinatedFiles(plan);
            planSimilarityService.store(input, plan);
            return plan;
        } catch (Exception e) {
            log.warn("[{}] AI planning failed ({}), using fallback plan", getName(), e.getMessage());
            return fallbackPlan(input);
        }
    }

    /**
     * Drop targetFiles paths that don't exist in the source ZIP.
     *
     * <p>The planner prompt instructs the AI to "include the surrounding build
     * + config files that wire Pub/Sub" — application.yml, application.properties,
     * pom.xml, etc.  Models obediently list those paths even when the source
     * project doesn't contain them, producing a plan with phantom files the
     * migrator can't process (it only iterates real source files).  Reviewers
     * then see entries in the plan for files that don't exist, which is
     * confusing and erodes trust.
     *
     * <p>Filter at the planner boundary so the rest of the pipeline never sees
     * hallucinated paths.  We don't surface "proposed new files" yet — when
     * the migrator gains the ability to genuinely synthesise a new file, that
     * file will appear in the diff viewer as CREATED with the loud banner
     * already in place on the frontend.
     */
    private MigrationPlan filterHallucinatedFiles(MigrationPlan plan) {
        if (plan.targetFiles().isEmpty() || plan.storageKey().isBlank()) {
            return plan;
        }
        Set<String> actualPaths;
        try {
            actualPaths = fileReader.listAllPaths(plan.storageKey());
        } catch (Exception e) {
            log.warn("[{}] could not list source paths to validate targetFiles ({}): keeping AI list as-is",
                    getName(), e.getMessage());
            return plan;
        }
        if (actualPaths.isEmpty()) return plan; // nothing to validate against

        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String path : plan.targetFiles()) {
            if (actualPaths.contains(path)) kept.add(path); else dropped.add(path);
        }
        if (!dropped.isEmpty()) {
            log.info("[{}] dropped {} hallucinated path(s) from targetFiles: {}",
                    getName(), dropped.size(), dropped);
        }
        if (kept.size() == plan.targetFiles().size()) return plan;

        return new MigrationPlan(
                plan.projectId(), plan.storageKey(), plan.targetStack(),
                plan.steps(), plan.riskLevel(), plan.estimatedEffort(),
                plan.summary(), kept);
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
