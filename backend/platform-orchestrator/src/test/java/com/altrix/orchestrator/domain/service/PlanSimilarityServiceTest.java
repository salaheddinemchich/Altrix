package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.port.out.PlanSimilarityCachePort;
import com.altrix.orchestrator.domain.port.out.PlanSimilarityCachePort.PlanSimilarityEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanSimilarityServiceTest {

    @Mock PlanSimilarityCachePort cache;

    private PlanSimilarityService service(double threshold) {
        return new PlanSimilarityService(cache, threshold);
    }

    private static MigrationPlan plan(String id) {
        return new MigrationPlan(id, "", "Stack", List.of(), "LOW", "1d", "sum", List.of(), null);
    }

    private static AnalysisReport report(String id, List<String> integrations) {
        return new AnalysisReport(id, "", List.of(), integrations, "summary", null);
    }

    // ── Jaccard ───────────────────────────────────────────────────────────────

    @Test
    void jaccard_identicalSets_returns1() {
        assertThat(PlanSimilarityService.jaccard(
                Set.of("a", "b", "c"), Set.of("a", "b", "c"))).isEqualTo(1.0);
    }

    @Test
    void jaccard_disjointSets_returns0() {
        assertThat(PlanSimilarityService.jaccard(
                Set.of("a"), Set.of("b"))).isEqualTo(0.0);
    }

    @Test
    void jaccard_bothEmpty_returns1() {
        assertThat(PlanSimilarityService.jaccard(Set.of(), Set.of())).isEqualTo(1.0);
    }

    @Test
    void jaccard_partialOverlap_calculatesCorrectly() {
        // intersection = {a,b}, union = {a,b,c,d} → 0.5
        double result = PlanSimilarityService.jaccard(
                Set.of("a", "b", "c"), Set.of("a", "b", "d"));
        assertThat(result).isEqualTo(2.0 / 4.0);
    }

    // ── findSimilar ───────────────────────────────────────────────────────────

    @Test
    void findSimilar_aboveThreshold_returnsCachedPlan() {
        MigrationPlan stored = plan("p-stored");
        when(cache.loadAll()).thenReturn(List.of(
                new PlanSimilarityEntry(List.of("google pub/sub", "spring boot"), "2", stored)));

        AnalysisReport input = report("p-new", List.of("Google Pub/Sub", "Spring Boot"));
        Optional<MigrationPlan> result = service(0.85).findSimilar(input);

        assertThat(result).contains(stored);
    }

    @Test
    void findSimilar_belowThreshold_returnsEmpty() {
        MigrationPlan stored = plan("p-stored");
        when(cache.loadAll()).thenReturn(List.of(
                new PlanSimilarityEntry(List.of("kafka", "spring boot", "redis"), "2", stored)));

        // Only 1 of 3 stored deps matches → Jaccard < 0.85
        AnalysisReport input = report("p-new", List.of("Google Pub/Sub"));
        Optional<MigrationPlan> result = service(0.85).findSimilar(input);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilar_differentSpringBootMajor_skipsEntry() {
        MigrationPlan stored = plan("p-stored");
        // Stored says Spring Boot 2; analysis detects Spring Boot 3
        when(cache.loadAll()).thenReturn(List.of(
                new PlanSimilarityEntry(List.of("google pub/sub", "spring boot 2"), "2", stored)));

        AnalysisReport input = report("p-new", List.of("Google Pub/Sub", "Spring Boot 3"));
        Optional<MigrationPlan> result = service(0.5).findSimilar(input);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilar_unknownMajorVersion_compatibleWithAny() {
        MigrationPlan stored = plan("p-stored");
        // Stored has no version info — should still match on dep similarity
        when(cache.loadAll()).thenReturn(List.of(
                new PlanSimilarityEntry(List.of("google pub/sub", "spring boot"), "", stored)));

        AnalysisReport input = report("p-new", List.of("Google Pub/Sub", "Spring Boot"));
        Optional<MigrationPlan> result = service(0.85).findSimilar(input);

        assertThat(result).contains(stored);
    }

    @Test
    void findSimilar_emptySignature_returnsEmpty() {
        // Empty dep signature short-circuits before loadAll — no stub needed
        AnalysisReport input = report("p-new", List.of());
        Optional<MigrationPlan> result = service(0.85).findSimilar(input);

        assertThat(result).isEmpty();
    }

    // ── extractSpringBootMajor ────────────────────────────────────────────────

    @Test
    void extractSpringBootMajor_fromIntegrations() {
        AnalysisReport report = report("p", List.of("Spring Boot 3.2.1", "Kafka"));
        assertThat(PlanSimilarityService.extractSpringBootMajor(report)).isEqualTo("3");
    }

    @Test
    void extractSpringBootMajor_fromSummary_whenNotInIntegrations() {
        AnalysisReport report = new AnalysisReport("p", "", List.of(), List.of("Kafka"),
                "This is a Spring Boot 2 application", null);
        assertThat(PlanSimilarityService.extractSpringBootMajor(report)).isEqualTo("2");
    }

    @Test
    void extractSpringBootMajor_notPresent_returnsBlank() {
        AnalysisReport report = report("p", List.of("Kafka", "PostgreSQL"));
        assertThat(PlanSimilarityService.extractSpringBootMajor(report)).isBlank();
    }
}
