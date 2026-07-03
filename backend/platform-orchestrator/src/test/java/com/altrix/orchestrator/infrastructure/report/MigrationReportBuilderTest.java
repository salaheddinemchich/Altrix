package com.altrix.orchestrator.infrastructure.report;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.ValidationReport.Finding;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFeature;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintRelationships;
import com.altrix.orchestrator.domain.model.blueprint.DetectedIntegration;
import com.altrix.orchestrator.domain.model.blueprint.DetectedStack;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry;
import com.altrix.orchestrator.domain.model.rag.FileProvenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationReportBuilderTest {

    private final MigrationReportBuilder builder = new MigrationReportBuilder();

    private MigratedFile file(String path, FileChangeType type, String diffSummary) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content("package x;\nclass A {}\n")
                .changeType(type).diffSummary(diffSummary).build();
    }

    private WorkflowOutcome baseOutcome(ValidationReport validation, List<MigratedFile> files) {
        return new WorkflowOutcome(
                "p1",
                new AnalysisReport("p1", "", List.of("OrderService"), List.of("GCP Pub/Sub Publisher"), "Analysis summary", null),
                new MigrationPlan("p1", "uploads/p1.zip", "Spring Boot 3 + Kafka",
                        List.of("Replace PubSubTemplate with KafkaTemplate"), "MEDIUM", "3 days", "Plan summary", List.of(), null),
                new MigrationArtifact("p1", files, "artifact summary", null),
                validation);
    }

    @Test
    void noAiCallsAnywhere_purelyDeterministic() {
        // No AiPort dependency exists on MigrationReportBuilder at all —
        // this test exists as documentation: if someone adds one, it'll be
        // an obvious constructor-signature change reviewers will catch.
        assertThat(MigrationReportBuilder.class.getDeclaredConstructors()).hasSize(1);
    }

    @Test
    void successfulMigration_producesAllSectionsAndApproveRecommendation() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "All checks passed", List.of());
        List<MigratedFile> files = List.of(
                file("src/main/java/OrderPublisher.java", FileChangeType.MODIFIED, "PubSubTemplate -> KafkaTemplate"),
                file("pom.xml", FileChangeType.MODIFIED, "Swapped GCP deps for spring-kafka"));
        WorkflowOutcome outcome = baseOutcome(validation, files);

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, null, null));

        assertThat(output.summary().status()).isEqualTo("SUCCESS");
        assertThat(output.summary().recommendation()).isEqualTo("APPROVE_FOR_DEPLOYMENT");
        assertThat(output.summary().confidenceScore()).isEqualTo(100);
        assertThat(output.summary().filesModified()).isEqualTo(2);

        String md = output.markdown();
        assertThat(md)
                .contains("# Migration Report")
                .contains("## Executive Summary")
                .contains("## Project Overview")
                .contains("## Migration Plan Summary")
                .contains("## File-Level Changes")
                .contains("## Architecture Transformation")
                .contains("## Dependency Changes")
                .contains("## Migration Decision Log")
                .contains("## Validation Results")
                .contains("## Detected Risks")
                .contains("## Remaining Manual Actions")
                .contains("## File Provenance")
                .contains("## Final Recommendation")
                .contains("APPROVE_FOR_DEPLOYMENT")
                .contains("OrderPublisher.java")
                .contains("PubSubTemplate -> KafkaTemplate");
    }

    @Test
    void failedValidation_recommendsDoNotDeploy() {
        ValidationReport validation = new ValidationReport("p1", false,
                List.of("OrderPublisher.java: Pub/Sub import not removed"), "1 issue found",
                List.of(new Finding("pubsub-leak", "ERROR", "OrderPublisher.java", 12, "Pub/Sub import not removed")));
        WorkflowOutcome outcome = baseOutcome(validation, List.of());

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, null, null));

        assertThat(output.summary().status()).isEqualTo("FAILED");
        assertThat(output.summary().recommendation()).isEqualTo("DO_NOT_DEPLOY");
        assertThat(output.summary().confidenceScore()).isEqualTo(55); // 100 - 30 (failed) - 15 (1 error)
        assertThat(output.markdown()).contains("FAILED").contains("Pub/Sub import not removed");
    }

    @Test
    void validationStages_onlyShowsDockerRunnersWhenTheyProducedFindings() {
        ValidationReport validation = new ValidationReport("p1", false,
                List.of(), "issues found", List.of(
                new Finding("docker", "ERROR", "pom.xml", 1, "compile error")));
        WorkflowOutcome outcome = baseOutcome(validation, List.of());

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, null, null));

        List<String> stageIds = output.summary().validationStages().stream()
                .map(s -> s.runnerId()).toList();
        // Always-on runners present even with zero findings.
        assertThat(stageIds).contains("contract", "pubsub-leak", "static", "migration-quality");
        // docker present (it produced a finding); docker-boot/docker-test absent (never ran).
        assertThat(stageIds).contains("docker");
        assertThat(stageIds).doesNotContain("docker-boot", "docker-test");

        boolean dockerPassed = output.summary().validationStages().stream()
                .filter(s -> s.runnerId().equals("docker")).findFirst().orElseThrow().passed();
        assertThat(dockerPassed).isFalse();
    }

    @Test
    void unmappedBlueprintFeature_surfacesAsLowRisk() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "ok", List.of());
        WorkflowOutcome outcome = baseOutcome(validation, List.of());

        BlueprintFeature unmapped = new BlueprintFeature("pubsub.test-iam", "IAM permission test",
                new BlueprintFeature.Evidence("testIAMPermissions", 42, "snippet"), null, List.of());
        BlueprintFile bf = new BlueprintFile("src/main/java/Foo.java", "Foo", BlueprintFile.Kind.CLASS,
                "com.example", "MessagingAdapter", BlueprintRelationships.empty(), List.of(unmapped), null);
        ProjectBlueprint blueprint = new ProjectBlueprint("p1", "s1", Instant.now(), 1,
                DetectedStack.unknown(), List.of(new DetectedIntegration("GCP Pub/Sub REST v1", "messaging", List.of())),
                List.of(bf), SemanticGraph.empty(), List.of(), List.of(), List.of("Custom retry logic detected"));

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, blueprint, null, null, null));

        assertThat(output.summary().risks()).anyMatch(r ->
                r.level().equals("LOW") && r.issue().contains("pubsub.test-iam"));
        assertThat(output.summary().risks()).anyMatch(r ->
                r.issue().contains("Custom retry logic detected"));
        // Risk present -> not a clean SUCCESS even though validation passed.
        assertThat(output.summary().status()).isEqualTo("PARTIAL");
        assertThat(output.summary().recommendation()).isEqualTo("MANUAL_REVIEW_REQUIRED");
    }

    @Test
    void todoAltrixMarkers_surfaceAsManualActions() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "ok", List.of());
        MigratedFile withTodo = MigratedFile.builder()
                .originalPath("src/main/java/Foo.java").newPath("src/main/java/Foo.java")
                .content("class Foo {\n  // TODO altrix: map IAM permissions manually via Kafka ACLs\n}\n")
                .changeType(FileChangeType.MODIFIED).diffSummary("partial migration").build();
        WorkflowOutcome outcome = baseOutcome(validation, List.of(withTodo));

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, null, null));

        assertThat(output.summary().manualActions())
                .anyMatch(a -> a.contains("Foo.java") && a.contains("TODO altrix"));
    }

    @Test
    void dependencyDiff_includedWhenProvided() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "ok", List.of());
        WorkflowOutcome outcome = baseOutcome(validation, List.of());
        DependencyDiffAnalyzer.Result diff = new DependencyDiffAnalyzer.Result(
                List.of("kafka-clients"), List.of("google-api-services-pubsub"));

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, null, diff));

        assertThat(output.summary().addedDependencies()).containsExactly("kafka-clients");
        assertThat(output.summary().removedDependencies()).containsExactly("google-api-services-pubsub");
        assertThat(output.markdown()).contains("kafka-clients").contains("google-api-services-pubsub");
        // Kafka dependency added -> standard manual-action boilerplate appended.
        assertThat(output.summary().manualActions()).anyMatch(a -> a.contains("Kafka topics"));
    }

    @Test
    void decisionLog_rendersRecordedDecisions() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "ok", List.of());
        WorkflowOutcome outcome = baseOutcome(validation, List.of());
        MigrationDecisionRegistry decisions = new MigrationDecisionRegistry("s1", List.of(
                MigrationDecision.replaceType("Pubsub", "KafkaProducer", "Legacy REST v1 client replaced")));

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, decisions, null, null));

        assertThat(output.summary().decisions()).hasSize(1);
        assertThat(output.summary().decisions().get(0).kind()).isEqualTo("REPLACE_TYPE");
        assertThat(output.markdown()).contains("REPLACE_TYPE").contains("KafkaProducer");
    }

    @Test
    void fileProvenance_rendersDocReferencesWhenPresent() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "ok", List.of());
        WorkflowOutcome outcome = baseOutcome(validation, List.of());
        FileProvenance provenance = new FileProvenance("s1",
                Map.of("src/main/java/Foo.java", List.of(
                        new FileProvenance.DocReference("kafka/producers", "https://kafka.apache.org/producers", "snippet"))),
                Instant.now());

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, provenance, null));

        assertThat(output.markdown())
                .contains("Foo.java")
                .contains("kafka/producers")
                .contains("https://kafka.apache.org/producers");
    }

    @Test
    void missingEnrichmentData_omitsThoseSectionsGracefully() {
        ValidationReport validation = new ValidationReport("p1", true, List.of(), "ok", List.of());
        WorkflowOutcome outcome = baseOutcome(validation, List.of());

        MigrationReportBuilder.Output output = builder.build(
                new MigrationReportBuilder.Input(outcome, null, null, null, null));

        assertThat(output.markdown())
                .contains("No recorded decisions for this session")
                .contains("No documentation provenance recorded for this session")
                .contains("No pom.xml dependency changes detected");
        assertThat(output.summary().decisions()).isEmpty();
    }
}
