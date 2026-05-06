package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 1 — Context Analyzer.
 *
 * <p>Reads source files from MinIO via {@link FileReaderPort} and calls the
 * fast AI model to identify components and integrations. Results are merged
 * with pre-scanned fields already present on {@link ProjectContext}
 * (listenerClasses, publisherClasses, pubSubTopics, pubSubSubscriptions).
 *
 * <p>Degrades gracefully: if storageKey is blank or the AI call fails,
 * the report is built from the pre-scanned context fields only.
 */
@Slf4j
@Component("contextAnalyzerAgent")
@RequiredArgsConstructor
public class ContextAnalyzerAgent implements MigrationAgent<ProjectContext, AnalysisReport> {

    private static final int    MAX_PROMPT_FILES = 10;
    private static final int    MAX_FILE_CHARS   = 4_000;
    private static final String SYSTEM_PROMPT    = """
            You are a software architect analysing a Java project for migration assessment.
            Examine the provided source files and identify:
            1. Components: fully-qualified or simple class names of listeners, publishers, services, controllers.
            2. Integrations: messaging systems (Pub/Sub, Kafka, RabbitMQ), databases, cloud APIs, frameworks.
            3. A concise technical summary (2–4 sentences).

            Return ONLY valid JSON — no markdown fences, no commentary:
            {"detectedComponents":["..."],"detectedIntegrations":["..."],"summary":"..."}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiPort         aiPort;
    private final FileReaderPort fileReader;

    @Override public String getName()  { return "Context Analyzer"; }
    @Override public int    getOrder() { return 1; }

    @Override
    public AnalysisReport execute(ProjectContext input) {
        if (input == null) throw new AgentFailureException(getName(), "input ProjectContext was null");
        log.info("[{}] analysing project '{}'", getName(), input.projectId());

        List<String> baseComponents   = new ArrayList<>(input.listenerClasses());
        baseComponents.addAll(input.publisherClasses());
        List<String> baseIntegrations = new ArrayList<>(input.pubSubTopics());
        baseIntegrations.addAll(input.pubSubSubscriptions());

        String storageKey = input.storageKey();
        if (storageKey == null || storageKey.isBlank()) {
            String summary = "Analyzed project '%s' — %d component(s), %d integration(s)"
                    .formatted(input.projectId(), baseComponents.size(), baseIntegrations.size());
            return new AnalysisReport(input.projectId(), "", baseComponents, baseIntegrations, summary);
        }

        try {
            Map<String, String> files       = fileReader.readSourceFiles(storageKey);
            String              userContent = buildFileContent(files);
            String              response    = aiPort.chatFast(SYSTEM_PROMPT, userContent);
            return parseAndMerge(input.projectId(), storageKey, response, baseComponents, baseIntegrations);
        } catch (Exception e) {
            log.warn("[{}] AI analysis failed ({}), falling back to context fields", getName(), e.getMessage());
            String summary = "Analyzed project '%s' — %d component(s), %d integration(s) (AI unavailable)"
                    .formatted(input.projectId(), baseComponents.size(), baseIntegrations.size());
            return new AnalysisReport(input.projectId(), storageKey, baseComponents, baseIntegrations, summary);
        }
    }

    private String buildFileContent(Map<String, String> files) {
        StringBuilder sb = new StringBuilder("Source files:\n\n");
        files.entrySet().stream()
                .limit(MAX_PROMPT_FILES)
                .forEach(e -> {
                    String content = e.getValue().length() > MAX_FILE_CHARS
                            ? e.getValue().substring(0, MAX_FILE_CHARS) + "\n... [truncated]"
                            : e.getValue();
                    sb.append("--- ").append(e.getKey()).append(" ---\n").append(content).append("\n\n");
                });
        return sb.toString();
    }

    private AnalysisReport parseAndMerge(String projectId, String storageKey, String response,
                                          List<String> base, List<String> baseIntegrations) {
        try {
            JsonNode root         = MAPPER.readTree(extractJson(response));
            List<String> aiComps  = toStringList(root.path("detectedComponents"));
            List<String> aiIntegs = toStringList(root.path("detectedIntegrations"));
            String summary        = root.path("summary").asText("");

            Set<String> mergedComps  = new LinkedHashSet<>(aiComps);
            mergedComps.addAll(base);
            Set<String> mergedIntegs = new LinkedHashSet<>(aiIntegs);
            mergedIntegs.addAll(baseIntegrations);

            return new AnalysisReport(projectId, storageKey,
                    List.copyOf(mergedComps), List.copyOf(mergedIntegs), summary);
        } catch (Exception e) {
            log.warn("[{}] failed to parse AI response, using base context: {}", getName(), e.getMessage());
            String summary = "Analyzed project '%s' — %d component(s) (parse error)"
                    .formatted(projectId, base.size());
            return new AnalysisReport(projectId, storageKey, base, baseIntegrations, summary);
        }
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
