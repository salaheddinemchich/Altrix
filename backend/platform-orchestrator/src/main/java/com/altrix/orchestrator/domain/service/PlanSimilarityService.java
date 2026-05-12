package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.port.out.PlanSimilarityCachePort;
import com.altrix.orchestrator.domain.port.out.PlanSimilarityCachePort.PlanSimilarityEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Domain service implementing plan-level semantic similarity caching (#155).
 *
 * <p>Before Agent 2 calls the AI, this service checks whether any previously
 * generated {@link MigrationPlan} matches the current project's dependency
 * signature with a Jaccard similarity ≥ the configured threshold.  A match
 * is only considered valid when both projects share the same Spring Boot
 * major version (or the version is unknown for one or both).
 *
 * <p>Similarity is computed over a normalised dependency signature derived
 * from {@link AnalysisReport#detectedIntegrations()} — lower-cased, sorted,
 * deduplicated.  The Jaccard formula is:
 * <pre>
 *   |A ∩ B| / |A ∪ B|
 * </pre>
 *
 * <p>This class is a pure-Java domain service: no Spring annotations.
 * It is wired as a {@code @Bean} in {@code BeanConfig}.
 */
public class PlanSimilarityService {

    private static final Pattern SB_VERSION_PATTERN =
            Pattern.compile("spring\\s+boot\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final PlanSimilarityCachePort cache;
    private final double threshold;

    public PlanSimilarityService(PlanSimilarityCachePort cache, double threshold) {
        this.cache = cache;
        this.threshold = threshold;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Looks up a cached plan similar to the current analysis report.
     * Returns an empty optional when no match meets the threshold.
     */
    public Optional<MigrationPlan> findSimilar(AnalysisReport report) {
        Set<String> sig = extractDependencySignature(report);
        String major = extractSpringBootMajor(report);
        if (sig.isEmpty()) return Optional.empty();

        List<PlanSimilarityEntry> entries = cache.loadAll();
        for (PlanSimilarityEntry entry : entries) {
            if (!versionsCompatible(major, entry.springBootMajor())) continue;
            double sim = jaccard(sig, new HashSet<>(entry.depSignature()));
            if (sim >= threshold) {
                return Optional.of(entry.plan());
            }
        }
        return Optional.empty();
    }

    /**
     * Stores a new plan in the similarity index after a successful AI call.
     * Best-effort — failures are ignored by the port implementation.
     */
    public void store(AnalysisReport report, MigrationPlan plan) {
        cache.store(
                extractDependencySignature(report),
                extractSpringBootMajor(report),
                plan);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Jaccard similarity between two sets.  Returns 1.0 when both are empty.
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    static Set<String> extractDependencySignature(AnalysisReport report) {
        return report.detectedIntegrations().stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    static String extractSpringBootMajor(AnalysisReport report) {
        for (String s : report.detectedIntegrations()) {
            Matcher m = SB_VERSION_PATTERN.matcher(s);
            if (m.find()) return m.group(1);
        }
        Matcher m = SB_VERSION_PATTERN.matcher(report.summary());
        return m.find() ? m.group(1) : "";
    }

    // Returns true when major versions are compatible (blank = unknown = compatible with any)
    private static boolean versionsCompatible(String majorA, String majorB) {
        if (majorA.isBlank() || majorB.isBlank()) return true;
        return majorA.equals(majorB);
    }
}
