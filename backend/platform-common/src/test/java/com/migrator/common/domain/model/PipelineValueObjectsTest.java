package com.migrator.common.domain.model;

import com.migrator.common.domain.enums.FileChangeType;
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
 *
 * <p>Each one defends against null collections / null fields and exposes a
 * {@code static empty(...)} (or equivalent) factory the stub agents rely on.
 */
class PipelineValueObjectsTest {

    // ── AnalysisReport ──────────────────────────────────────────────────────

    @Test
    void analysisReport_replacesNullListsWithEmpty() {
        AnalysisReport r = new AnalysisReport("p1", null, null, null);
        assertThat(r.detectedComponents()).isEmpty();
        assertThat(r.detectedIntegrations()).isEmpty();
        assertThat(r.summary()).isEmpty();
    }

    @Test
    void analysisReport_isDefensivelyImmutable() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        AnalysisReport r = new AnalysisReport("p1", mutable, List.of(), "");
        assertThatThrownBy(() -> r.detectedComponents().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        mutable.add("post-construction");
        assertThat(r.detectedComponents()).containsExactly("a");
    }

    @Test
    void analysisReport_emptyFactory() {
        AnalysisReport r = AnalysisReport.empty("p1");
        assertThat(r.projectId()).isEqualTo("p1");
        assertThat(r.detectedComponents()).isEmpty();
    }

    // ── MigrationPlan ───────────────────────────────────────────────────────

    @Test
    void migrationPlan_handlesNullsAndDefensiveCopy() {
        MigrationPlan p = new MigrationPlan("p1", null, null);
        assertThat(p.steps()).isEmpty();
        assertThat(p.summary()).isEmpty();
    }

    @Test
    void migrationPlan_emptyFactory() {
        assertThat(MigrationPlan.empty("p1").projectId()).isEqualTo("p1");
    }

    // ── ApprovedPlan ────────────────────────────────────────────────────────

    @Test
    void approvedPlan_rejectsNullPlan() {
        assertThatThrownBy(() -> new ApprovedPlan(null, "user", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedPlan_defaultsApprovedByToAuto() {
        ApprovedPlan ap = new ApprovedPlan(MigrationPlan.empty("p1"), null, null);
        assertThat(ap.approvedBy()).isEqualTo("auto");
        assertThat(ap.approvedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void approvedPlan_autoApprovedFactoryUsesNow() {
        ApprovedPlan ap = ApprovedPlan.autoApproved(MigrationPlan.empty("p1"));
        assertThat(ap.approvedBy()).isEqualTo("auto");
        assertThat(ap.approvedAt()).isAfter(Instant.EPOCH);
    }

    // ── MigrationArtifact ───────────────────────────────────────────────────

    @Test
    void migrationArtifact_handlesNullsAndDefensiveCopy() {
        MigrationArtifact a = new MigrationArtifact("p1", null, null);
        assertThat(a.files()).isEmpty();
        assertThat(a.summary()).isEmpty();
    }

    @Test
    void migrationArtifact_carriesFiles() {
        MigratedFile f = MigratedFile.builder()
                .originalPath("a.java").newPath("a.java")
                .content("//x").changeType(FileChangeType.MODIFIED)
                .diffSummary("x")
                .build();
        MigrationArtifact a = new MigrationArtifact("p1", List.of(f), "did stuff");
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
        MigrationReport r = new MigrationReport("p1", null, null);
        assertThat(r.content()).isEmpty();
        assertThat(r.generatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void migrationReport_emptyFactoryUsesNow() {
        MigrationReport r = MigrationReport.empty("p1");
        assertThat(r.generatedAt()).isAfter(Instant.EPOCH);
    }
}
