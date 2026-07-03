package com.altrix.common.domain.model;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the value objects added to support the typed agent pipeline:
 * AnalysisReport · MigrationPlan · ApprovedPlan · MigrationArtifact ·
 * ValidationReport · WorkflowOutcome · MigrationReport.
 */
class PipelineValueObjectsTest {

    // ── AnalysisReport ──────────────────────────────────────────────────────

    @Test
    void analysisReport_replacesNullListsWithEmpty() {
        AnalysisReport r = new AnalysisReport("p1", null, null, null, null, null);
        assertThat(r.storageKey()).isEmpty();
        assertThat(r.detectedComponents()).isEmpty();
        assertThat(r.detectedIntegrations()).isEmpty();
        assertThat(r.summary()).isEmpty();
        assertThat(r.jakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
    }

    @Test
    void analysisReport_isDefensivelyImmutable() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        AnalysisReport r = new AnalysisReport("p1", null, mutable, List.of(), "", null);
        assertThatThrownBy(() -> r.detectedComponents().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        mutable.add("post-construction");
        assertThat(r.detectedComponents()).containsExactly("a");
    }

    @Test
    void analysisReport_propagatesStorageKey() {
        AnalysisReport r = new AnalysisReport("p1", "uploads/p1.zip", List.of(), List.of(), "", null);
        assertThat(r.storageKey()).isEqualTo("uploads/p1.zip");
    }

    @Test
    void analysisReport_emptyFactory() {
        AnalysisReport r = AnalysisReport.empty("p1");
        assertThat(r.projectId()).isEqualTo("p1");
        assertThat(r.storageKey()).isEmpty();
        assertThat(r.detectedComponents()).isEmpty();
    }

    // ── MigrationPlan ───────────────────────────────────────────────────────

    @Test
    void migrationPlan_handlesNullsAndDefensiveCopy() {
        MigrationPlan p = new MigrationPlan("p1", null, null, null, null, null, null, null, null);
        assertThat(p.storageKey()).isEmpty();
        assertThat(p.targetStack()).isEmpty();
        assertThat(p.steps()).isEmpty();
        assertThat(p.riskLevel()).isEmpty();
        assertThat(p.estimatedEffort()).isEmpty();
        assertThat(p.summary()).isEmpty();
        assertThat(p.jakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
    }

    @Test
    void migrationPlan_preservesAllFields() {
        MigrationPlan p = new MigrationPlan("p1", "uploads/p1.zip", "Spring Boot 3 + Kafka",
                List.of("Step 1"), "MEDIUM", "2 days", "migrate messaging layer", List.of(), null);
        assertThat(p.storageKey()).isEqualTo("uploads/p1.zip");
        assertThat(p.targetStack()).isEqualTo("Spring Boot 3 + Kafka");
        assertThat(p.steps()).containsExactly("Step 1");
        assertThat(p.riskLevel()).isEqualTo("MEDIUM");
        assertThat(p.estimatedEffort()).isEqualTo("2 days");
        assertThat(p.summary()).isEqualTo("migrate messaging layer");
    }

    @Test
    void migrationPlan_emptyFactory() {
        MigrationPlan p = MigrationPlan.empty("p1");
        assertThat(p.projectId()).isEqualTo("p1");
        assertThat(p.storageKey()).isEmpty();
        assertThat(p.steps()).isEmpty();
    }

    // ── ApprovedPlan ────────────────────────────────────────────────────────

    @Test
    void approvedPlan_rejectsNullPlan() {
        assertThatThrownBy(() -> new ApprovedPlan(null, "user", Instant.now(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedPlan_defaultsApprovedByToAuto() {
        ApprovedPlan ap = new ApprovedPlan(MigrationPlan.empty("p1"), null, null, null, null);
        assertThat(ap.approvedBy()).isEqualTo("auto");
        assertThat(ap.approvedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void approvedPlan_autoApprovedFactoryUsesNow() {
        ApprovedPlan ap = ApprovedPlan.autoApproved(MigrationPlan.empty("p1"));
        assertThat(ap.approvedBy()).isEqualTo("auto");
        assertThat(ap.approvedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void approvedPlan_failingPathsDefaultsToEmpty_onLegacyShapes() {
        // Back-compat 5-arg constructor and the 2-arg withRetryContext overload
        // must yield an empty (never null) failingPaths list.
        ApprovedPlan legacy = new ApprovedPlan(MigrationPlan.empty("p1"), "user", Instant.now(), null, null);
        assertThat(legacy.failingPaths()).isEmpty();
        assertThat(legacy.withRetryContext("ctx").failingPaths()).isEmpty();
        assertThat(legacy.withRetryContext("ctx", null).failingPaths()).isEmpty();
    }

    @Test
    void approvedPlan_withRetryContext_carriesFailingPaths() {
        ApprovedPlan ap = ApprovedPlan.autoApproved(MigrationPlan.empty("p1"))
                .withRetryContext("ctx", null, List.of("src/A.java"));
        assertThat(ap.failingPaths()).containsExactly("src/A.java");
        assertThat(ap.retryContext()).isEqualTo("ctx");
    }

    // ── MigrationArtifact ───────────────────────────────────────────────────

    @Test
    void migrationArtifact_handlesNullsAndDefensiveCopy() {
        MigrationArtifact a = new MigrationArtifact("p1", null, null, null);
        assertThat(a.files()).isEmpty();
        assertThat(a.summary()).isEmpty();
        assertThat(a.jakartaMessagingTarget()).isEqualTo(JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
    }

    @Test
    void migrationArtifact_carriesFiles() {
        MigratedFile f = MigratedFile.builder()
                .originalPath("a.java").newPath("a.java")
                .content("//x").changeType(FileChangeType.MODIFIED)
                .diffSummary("x")
                .build();
        MigrationArtifact a = new MigrationArtifact("p1", List.of(f), "did stuff", null);
        assertThat(a.files()).hasSize(1);
        assertThat(a.summary()).isEqualTo("did stuff");
    }

    // ── ValidationReport ────────────────────────────────────────────────────

    @Test
    void validationReport_pendingFactory_isPassedAndStub() {
        ValidationReport v = ValidationReport.pending("p1");
        assertThat(v.passed()).isTrue();
        assertThat(v.failures()).isEmpty();
        assertThat(v.summary()).contains("stub");
    }

    @Test
    void validationReport_failureCarriesFindings() {
        ValidationReport v = new ValidationReport("p1", false, List.of("compile error"), "x");
        assertThat(v.passed()).isFalse();
        assertThat(v.failures()).containsExactly("compile error");
    }

    @Test
    void validationReport_failingFilePaths_extractsNormalisedErrorPaths() {
        ValidationReport v = new ValidationReport("p1", false, List.of(), "x", List.of(
                // docker runner shape — already workspace-relative
                new ValidationReport.Finding("docker", "ERROR", "src/main/java/p/A.java", 12, "cannot find symbol"),
                // un-stripped absolute sandbox path → normalised
                new ValidationReport.Finding("docker", "ERROR", "/workspace/src/main/java/p/B.java", 3, "type error"),
                // duplicate of A (second error in same file) → deduped
                new ValidationReport.Finding("docker", "ERROR", "src/main/java/p/A.java", 40, "second error"),
                // Windows separators → unified
                new ValidationReport.Finding("static", "ERROR", "src\\main\\java\\p\\C.java", -1, "leak"),
                // non-ERROR severity → excluded
                new ValidationReport.Finding("docker", "WARNING", "src/main/java/p/D.java", 1, "warn"),
                // project-wide finding (no path) → excluded
                new ValidationReport.Finding("docker-boot", "ERROR", null, -1, "boot timed out")));

        assertThat(v.failingFilePaths()).containsExactly(
                "src/main/java/p/A.java",
                "src/main/java/p/B.java",
                "src/main/java/p/C.java");
    }

    @Test
    void validationReport_failingFilePaths_emptyWhenNoPerFileFindings() {
        ValidationReport bootTimeout = new ValidationReport("p1", false, List.of("boot timed out"), "x",
                List.of(new ValidationReport.Finding("docker-boot", "ERROR", null, -1, "exit 124")));
        assertThat(bootTimeout.failingFilePaths()).isEmpty();
        assertThat(ValidationReport.pending("p1").failingFilePaths()).isEmpty();
    }

    // ── WorkflowOutcome ─────────────────────────────────────────────────────

    @Test
    void workflowOutcome_replacesNullSubcomponentsWithDefaults() {
        WorkflowOutcome o = new WorkflowOutcome("p1", null, null, null, null);
        assertThat(o.analysis()).isNotNull();
        assertThat(o.plan()).isNotNull();
        assertThat(o.artifact()).isNotNull();
        assertThat(o.validation()).isNotNull();
        assertThat(o.validation().passed()).isTrue();
    }

    // ── MigrationReport ─────────────────────────────────────────────────────

    @Test
    void migrationReport_handlesNulls() {
        MigrationReport r = new MigrationReport("p1", null, null, null);
        assertThat(r.content()).isEmpty();
        assertThat(r.generatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(r.summary()).isEqualTo(ReportSummary.empty());
    }

    @Test
    void migrationReport_emptyFactoryUsesNow() {
        MigrationReport r = MigrationReport.empty("p1");
        assertThat(r.generatedAt()).isAfter(Instant.EPOCH);
    }
}
