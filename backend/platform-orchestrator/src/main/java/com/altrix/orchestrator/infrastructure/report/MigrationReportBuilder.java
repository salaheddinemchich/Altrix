package com.altrix.orchestrator.infrastructure.report;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.ReportSummary;
import com.altrix.common.domain.model.ReportSummary.DecisionLogEntry;
import com.altrix.common.domain.model.ReportSummary.RiskItem;
import com.altrix.common.domain.model.ReportSummary.ValidationStageResult;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.ValidationReport.Finding;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFeature;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.DetectedIntegration;
import com.altrix.orchestrator.domain.model.blueprint.DetectedStack;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry;
import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the deterministic migration report — both the full Markdown
 * document ({@code content}) and its structured companion ({@link
 * ReportSummary}). Every figure here is computed from data the pipeline
 * already produced; nothing is AI-generated or invented.
 *
 * <p>All enrichment inputs ({@code blueprint}, {@code decisions}, {@code
 * provenance}, {@code dependencyDiff}) are optional — {@link
 * com.altrix.orchestrator.agent.impl.ReportGeneratorAgent} fetches them
 * best-effort and a missing one simply omits its section, the same
 * null-guarded pattern {@code CoreMigratorAgent.resolveBlueprint()} uses.
 */
@Component
public class MigrationReportBuilder {

    /** Stable runner ids that run unconditionally (defense-in-depth) regardless of sandbox.docker.enabled. */
    private static final List<String> ALWAYS_ON_RUNNERS = List.of("contract", "pubsub-leak", "static", "migration-quality");

    private static final Map<String, String> RUNNER_LABELS = Map.of(
            "contract", "Contract Validation",
            "pubsub-leak", "Pub/Sub Leak Validation",
            "static", "Static Analysis",
            "docker", "Docker Compile",
            "docker-boot", "Docker Boot Health",
            "docker-test", "Unit Tests",
            "migration-quality", "Migration Quality"
    );

    /** Ordered, case-insensitive substring → Kafka-equivalent label for the Architecture Transformation section. */
    private static final List<Map.Entry<String, String>> INTEGRATION_TARGETS = List.of(
            Map.entry("dead letter", "Kafka dead-letter topic + retry-count header pattern"),
            Map.entry("publisher", "Kafka Producer (KafkaTemplate / KafkaProducer)"),
            Map.entry("subscriber", "Kafka Consumer (@KafkaListener / KafkaConsumer)"),
            Map.entry("pull", "Kafka Consumer (manual poll loop)"),
            Map.entry("push", "Kafka Consumer (@KafkaListener)"),
            Map.entry("pub/sub", "Apache Kafka"),
            Map.entry("pubsub", "Apache Kafka")
    );

    private static final Pattern TODO_ALTRIX = Pattern.compile("//\\s*TODO altrix:.*", Pattern.MULTILINE);

    public record Input(
            WorkflowOutcome outcome,
            ProjectBlueprint blueprint,
            MigrationDecisionRegistry decisions,
            FileProvenance provenance,
            DependencyDiffAnalyzer.Result dependencyDiff
    ) {
        public Input {
            if (dependencyDiff == null) dependencyDiff = DependencyDiffAnalyzer.Result.EMPTY;
        }
    }

    public record Output(String markdown, ReportSummary summary) {
    }

    public Output build(Input input) {
        WorkflowOutcome outcome = input.outcome();
        AnalysisReport analysis = outcome.analysis();
        MigrationPlan plan = outcome.plan();
        MigrationArtifact artifact = outcome.artifact();
        ValidationReport validation = outcome.validation();
        ProjectBlueprint blueprint = input.blueprint();

        Map<FileChangeType, Long> counts = countByChangeType(artifact);
        List<ValidationStageResult> stages = buildValidationStages(validation);
        List<RiskItem> risks = buildRisks(validation, blueprint);
        List<String> manualActions = buildManualActions(artifact, input.dependencyDiff());
        List<DecisionLogEntry> decisionEntries = buildDecisionLog(input.decisions());

        boolean compilePassed = noErrorFor(validation, "docker");
        boolean bootPassed = noErrorFor(validation, "docker-boot");
        boolean testsPassed = noErrorFor(validation, "docker-test");

        String status;
        String recommendation;
        if (!validation.passed()) {
            status = "FAILED";
            recommendation = "DO_NOT_DEPLOY";
        } else if (risks.isEmpty() && manualActions.isEmpty()) {
            status = "SUCCESS";
            recommendation = "APPROVE_FOR_DEPLOYMENT";
        } else {
            status = "PARTIAL";
            recommendation = "MANUAL_REVIEW_REQUIRED";
        }

        int confidenceScore = computeConfidenceScore(validation, risks);

        List<String> detectedIntegrationNames = blueprint != null && !blueprint.detectedIntegrations().isEmpty()
                ? blueprint.detectedIntegrations().stream().map(DetectedIntegration::name).toList()
                : analysis.detectedIntegrations();

        ReportSummary summary = new ReportSummary(
                status, confidenceScore,
                artifact.files().size(),
                counts.getOrDefault(FileChangeType.MODIFIED, 0L).intValue(),
                counts.getOrDefault(FileChangeType.CREATED, 0L).intValue(),
                counts.getOrDefault(FileChangeType.DELETED, 0L).intValue(),
                counts.getOrDefault(FileChangeType.UNCHANGED, 0L).intValue(),
                compilePassed, bootPassed, testsPassed,
                plan.targetStack(), plan.riskLevel(), plan.estimatedEffort(),
                analysis.detectedComponents(), detectedIntegrationNames, plan.steps(),
                input.dependencyDiff().added(), input.dependencyDiff().removed(),
                stages, risks, manualActions, decisionEntries,
                recommendation
        );

        String markdown = buildMarkdown(input, summary, counts);
        return new Output(markdown, summary);
    }

    // ── section builders ───────────────────────────────────────────────────

    private Map<FileChangeType, Long> countByChangeType(MigrationArtifact artifact) {
        return artifact.files().stream()
                .collect(Collectors.groupingBy(MigratedFile::changeType, Collectors.counting()));
    }

    private boolean noErrorFor(ValidationReport validation, String runnerId) {
        return validation.findings().stream()
                .noneMatch(f -> runnerId.equals(f.runnerId()) && "ERROR".equals(f.severity()));
    }

    /**
     * One row per always-on runner (shown even with zero findings — they
     * genuinely always run) plus one row per opt-in Docker runner ONLY when
     * it produced at least one finding (the only signal we have that it
     * actually ran; {@code sandbox.docker.enabled=false} means it never
     * executes at all, and we don't fabricate a PASSED row for a stage that
     * never ran).
     */
    private List<ValidationStageResult> buildValidationStages(ValidationReport validation) {
        Map<String, List<Finding>> byRunner = validation.findings().stream()
                .collect(Collectors.groupingBy(Finding::runnerId, LinkedHashMap::new, Collectors.toList()));

        LinkedHashSet<String> runnerIds = new LinkedHashSet<>(ALWAYS_ON_RUNNERS);
        runnerIds.addAll(byRunner.keySet());

        List<ValidationStageResult> stages = new ArrayList<>();
        for (String runnerId : runnerIds) {
            List<Finding> findings = byRunner.getOrDefault(runnerId, List.of());
            long errors = findings.stream().filter(f -> "ERROR".equals(f.severity())).count();
            long warnings = findings.stream().filter(f -> "WARNING".equals(f.severity())).count();
            stages.add(new ValidationStageResult(
                    runnerId, RUNNER_LABELS.getOrDefault(runnerId, runnerId),
                    errors == 0, (int) errors, (int) warnings));
        }
        return stages;
    }

    private List<RiskItem> buildRisks(ValidationReport validation, ProjectBlueprint blueprint) {
        List<RiskItem> risks = new ArrayList<>();

        validation.findings().stream()
                .filter(f -> "WARNING".equals(f.severity()))
                .forEach(f -> risks.add(new RiskItem("MEDIUM", f.filePath(), f.message(),
                        "Manual review recommended.")));

        if (blueprint != null) {
            blueprint.riskNotes().forEach(note ->
                    risks.add(new RiskItem("MEDIUM", null, note, "Manual review recommended.")));

            for (BlueprintFile file : blueprint.files()) {
                for (BlueprintFeature feature : file.features()) {
                    if (feature.kafkaTarget() == null || feature.kafkaTarget().isBlank()) {
                        risks.add(new RiskItem("LOW", file.path(),
                                "Pub/Sub feature '" + feature.id() + "' (" + feature.description()
                                        + ") has no direct Kafka equivalent.",
                                "Manual review recommended — see migration notes for this file."));
                    }
                }
            }
        }
        return risks;
    }

    private List<String> buildManualActions(MigrationArtifact artifact, DependencyDiffAnalyzer.Result dependencyDiff) {
        List<String> actions = new ArrayList<>();
        for (MigratedFile file : artifact.files()) {
            Matcher m = TODO_ALTRIX.matcher(file.content());
            while (m.find()) {
                actions.add(file.newPath() + ": " + m.group().trim());
            }
        }
        boolean kafkaAdded = dependencyDiff.added().stream()
                .anyMatch(a -> a.contains("kafka"));
        if (kafkaAdded) {
            actions.add("Create the required Kafka topics in your target cluster.");
            actions.add("Configure production bootstrap servers (replace any localhost:9092 defaults).");
            actions.add("Validate monitoring/alerting dashboards reflect the new Kafka topics.");
            actions.add("Perform end-to-end UAT testing before production cutover.");
        }
        return actions;
    }

    private List<DecisionLogEntry> buildDecisionLog(MigrationDecisionRegistry decisions) {
        if (decisions == null) return List.of();
        return decisions.decisions().stream()
                .map(d -> new DecisionLogEntry(d.kind().name(), d.from(), d.to(), d.scope(), d.rationale()))
                .toList();
    }

    private int computeConfidenceScore(ValidationReport validation, List<RiskItem> risks) {
        int score = 100;
        if (!validation.passed()) score -= 30;
        long errors = validation.findings().stream().filter(f -> "ERROR".equals(f.severity())).count();
        score -= Math.min(60, (int) errors * 15);
        long warnings = validation.findings().stream().filter(f -> "WARNING".equals(f.severity())).count();
        score -= Math.min(15, (int) warnings * 3);
        long unmapped = risks.stream().filter(r -> "LOW".equals(r.level())).count();
        score -= Math.min(20, (int) unmapped * 5);
        return Math.max(0, Math.min(100, score));
    }

    private String kafkaEquivalentFor(String integrationName) {
        String lower = integrationName.toLowerCase();
        for (Map.Entry<String, String> entry : INTEGRATION_TARGETS) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return "Apache Kafka equivalent";
    }

    // ── markdown rendering ───────────────────────────────────────────────────

    private String buildMarkdown(Input input, ReportSummary s, Map<FileChangeType, Long> counts) {
        WorkflowOutcome outcome = input.outcome();
        AnalysisReport analysis = outcome.analysis();
        MigrationPlan plan = outcome.plan();
        MigrationArtifact artifact = outcome.artifact();
        ValidationReport validation = outcome.validation();
        ProjectBlueprint blueprint = input.blueprint();

        StringBuilder sb = new StringBuilder();
        sb.append("# Migration Report\n\n");

        // ── Executive Summary ────────────────────────────────────────────
        sb.append("## Executive Summary\n\n");
        sb.append("| Field | Value |\n|-------|-------|\n");
        sb.append("| **Project** | ").append(outcome.projectId()).append(" |\n");
        sb.append("| **Status** | ").append(s.status()).append(" |\n");
        sb.append("| **Files Analyzed** | ").append(s.filesAnalyzed()).append(" |\n");
        sb.append("| **Files Modified** | ").append(s.filesModified()).append(" |\n");
        sb.append("| **Files Created** | ").append(s.filesCreated()).append(" |\n");
        sb.append("| **Files Deleted** | ").append(s.filesDeleted()).append(" |\n");
        sb.append("| **Compile Status** | ").append(s.compilePassed() ? "PASSED" : "FAILED").append(" |\n");
        sb.append("| **Boot Status** | ").append(s.bootPassed() ? "PASSED" : "FAILED").append(" |\n");
        sb.append("| **Tests** | ").append(s.testsPassed() ? "PASSED" : "FAILED").append(" |\n");
        sb.append("| **Migration Confidence Score** | ").append(s.confidenceScore()).append("% |\n");
        sb.append("| **Recommendation** | ").append(s.recommendation()).append(" |\n\n");

        // ── Project Overview ─────────────────────────────────────────────
        sb.append("## Project Overview\n\n");
        if (!analysis.summary().isBlank()) sb.append(analysis.summary()).append("\n\n");
        if (blueprint != null) {
            DetectedStack stack = blueprint.detectedStack();
            sb.append("**Detected Stack**:\n");
            if (stack != null) {
                if (stack.language() != null) sb.append("- Language: ").append(stack.language())
                        .append(stack.languageVersion() != null ? " " + stack.languageVersion() : "").append("\n");
                if (stack.framework() != null) sb.append("- Framework: ").append(stack.framework()).append("\n");
                if (stack.buildSystem() != null) sb.append("- Build system: ").append(stack.buildSystem()).append("\n");
                if (stack.runtime() != null) sb.append("- Runtime: ").append(stack.runtime()).append("\n");
            }
            sb.append("\n");
        }
        if (!s.detectedComponents().isEmpty()) {
            sb.append("**Components**: ").append(String.join(", ", s.detectedComponents())).append("\n");
        }
        if (!s.detectedIntegrations().isEmpty()) {
            sb.append("**Integrations**: ").append(String.join(", ", s.detectedIntegrations())).append("\n");
        }
        sb.append("\n**Migration Target**: Apache Kafka").append(
                plan.targetStack().isBlank() ? "" : " (" + plan.targetStack() + ")").append("\n\n");

        // ── Migration Plan Summary ───────────────────────────────────────
        sb.append("## Migration Plan Summary\n\n");
        if (!plan.summary().isBlank()) sb.append(plan.summary()).append("\n\n");
        if (!plan.riskLevel().isBlank()) sb.append("**Risk level**: ").append(plan.riskLevel()).append("\n");
        if (!plan.estimatedEffort().isBlank()) sb.append("**Estimated effort**: ").append(plan.estimatedEffort()).append("\n");
        sb.append("\n");
        if (!plan.steps().isEmpty()) {
            int i = 1;
            for (String step : plan.steps()) sb.append(i++).append(". ").append(step).append("\n");
            sb.append("\n");
        }

        // ── File-Level Changes ───────────────────────────────────────────
        sb.append("## File-Level Changes\n\n");
        if (artifact.files().isEmpty()) {
            sb.append("_No files processed._\n\n");
        } else {
            sb.append("| File | Change | Notes |\n|------|--------|-------|\n");
            artifact.files().forEach(f -> sb.append("| `").append(f.newPath()).append("` | ")
                    .append(f.changeType()).append(" | ")
                    .append(f.diffSummary() == null ? "" : f.diffSummary()).append(" |\n"));
            sb.append("\n**Summary**: ").append(s.filesModified()).append(" modified, ")
                    .append(s.filesCreated()).append(" created, ")
                    .append(s.filesDeleted()).append(" deleted, ")
                    .append(counts.getOrDefault(FileChangeType.UNCHANGED, 0L)).append(" unchanged\n\n");
        }

        // ── Architecture Transformation ──────────────────────────────────
        sb.append("## Architecture Transformation\n\n");
        if (s.detectedIntegrations().isEmpty()) {
            sb.append("_No messaging integrations detected._\n\n");
        } else {
            sb.append("| Before | After |\n|--------|-------|\n");
            for (String integration : s.detectedIntegrations()) {
                sb.append("| ").append(integration).append(" | ").append(kafkaEquivalentFor(integration)).append(" |\n");
            }
            sb.append("\n");
        }
        if (blueprint != null) {
            Map<String, Long> roleCounts = blueprint.files().stream()
                    .filter(f -> !f.isPassThrough())
                    .collect(Collectors.groupingBy(BlueprintFile::role, LinkedHashMap::new, Collectors.counting()));
            if (!roleCounts.isEmpty()) {
                sb.append("**Migrated by role**:\n");
                roleCounts.forEach((role, count) -> sb.append("- ").append(role).append(": ")
                        .append(count).append(" file(s)\n"));
                sb.append("\n");
            }
        }

        // ── Dependency Changes ────────────────────────────────────────────
        sb.append("## Dependency Changes\n\n");
        if (s.addedDependencies().isEmpty() && s.removedDependencies().isEmpty()) {
            sb.append("_No pom.xml dependency changes detected (or project does not use Maven)._\n\n");
        } else {
            if (!s.removedDependencies().isEmpty()) {
                sb.append("**Removed**:\n");
                s.removedDependencies().forEach(d -> sb.append("- ").append(d).append("\n"));
                sb.append("\n");
            }
            if (!s.addedDependencies().isEmpty()) {
                sb.append("**Added**:\n");
                s.addedDependencies().forEach(d -> sb.append("- ").append(d).append("\n"));
                sb.append("\n");
            }
        }

        // ── Migration Decision Log ───────────────────────────────────────
        sb.append("## Migration Decision Log\n\n");
        if (s.decisions().isEmpty()) {
            sb.append("_No recorded decisions for this session._\n\n");
        } else {
            sb.append("| Kind | From | To | Scope | Rationale |\n|------|------|----|-------|-----------|\n");
            s.decisions().forEach(d -> sb.append("| ").append(d.kind()).append(" | `")
                    .append(d.from()).append("` | `").append(d.to()).append("` | ")
                    .append(d.scope() == null ? "—" : d.scope()).append(" | ")
                    .append(d.rationale() == null ? "" : d.rationale()).append(" |\n"));
            sb.append("\n");
        }

        // ── Validation Results ───────────────────────────────────────────
        sb.append("## Validation Results\n\n");
        sb.append("**Overall result**: ").append(validation.passed() ? "PASSED ✓" : "FAILED ✗").append("\n\n");
        sb.append("| Stage | Result | Errors | Warnings |\n|-------|--------|--------|----------|\n");
        s.validationStages().forEach(stage -> sb.append("| ").append(stage.label()).append(" | ")
                .append(stage.passed() ? "PASSED" : "FAILED").append(" | ")
                .append(stage.errorCount()).append(" | ").append(stage.warningCount()).append(" |\n"));
        sb.append("\n");
        // Back-compat: validation.failures() is the flat-string view some
        // callers populate without structured findings — still worth
        // surfacing so issues aren't silently dropped from the report.
        if (!validation.failures().isEmpty()) {
            sb.append("**Issues**:\n");
            validation.failures().forEach(f -> sb.append("- ").append(f).append("\n"));
            sb.append("\n");
        }
        sb.append("_").append(validation.summary()).append("_\n\n");

        // ── Detected Risks ────────────────────────────────────────────────
        sb.append("## Detected Risks\n\n");
        if (s.risks().isEmpty()) {
            sb.append("_No risks detected._\n\n");
        } else {
            s.risks().forEach(r -> {
                sb.append("**Risk Level: ").append(r.level()).append("**\n");
                if (r.file() != null) sb.append("- File: `").append(r.file()).append("`\n");
                sb.append("- Issue: ").append(r.issue()).append("\n");
                sb.append("- Recommendation: ").append(r.recommendation()).append("\n\n");
            });
        }

        // ── Remaining Manual Actions ──────────────────────────────────────
        sb.append("## Remaining Manual Actions\n\n");
        if (s.manualActions().isEmpty()) {
            sb.append("_No manual follow-up actions identified._\n\n");
        } else {
            int i = 1;
            for (String action : s.manualActions()) sb.append(i++).append(". ").append(action).append("\n");
            sb.append("\n");
        }

        // ── File Provenance ───────────────────────────────────────────────
        sb.append("## File Provenance\n\n");
        FileProvenance provenance = input.provenance();
        if (provenance == null || provenance.perFile().isEmpty()) {
            sb.append("_No documentation provenance recorded for this session._\n\n");
        } else {
            provenance.perFile().forEach((path, refs) -> {
                if (refs.isEmpty()) return;
                sb.append("**`").append(path).append("`**\n");
                refs.forEach(ref -> sb.append("- ").append(ref.logicalPath())
                        .append(" — ").append(ref.sourceUrl()).append("\n"));
                sb.append("\n");
            });
        }

        // ── Final Recommendation ─────────────────────────────────────────
        sb.append("## Final Recommendation\n\n");
        sb.append("**Overall Assessment**: ").append(s.status()).append("\n\n");
        sb.append("**Confidence Score**: ").append(s.confidenceScore()).append("%\n\n");
        sb.append("**Recommended Action**: ").append(s.recommendation()).append("\n");

        return sb.toString();
    }
}
