package com.altrix.orchestrator.infrastructure.migration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Golden-eval scorer: measures how close a migration output is to a
 * hand-migrated REFERENCE baseline (e.g. {@code ../test-altrix-kafka}).
 *
 * <p>"Does it compile" is a floor, not a quality bar.  A good Pub/Sub→Kafka
 * migration also (a) leaves ZERO Pub/Sub residue, (b) adopts Kafka idioms,
 * and (c) <i>consolidates</i> toward the idiomatic shape — the reference
 * deleted ~14 files and merged the custom messaging framework.  This scorer
 * turns those into interpretable numbers so we can tell whether a change
 * actually moved migration quality, not just the compile-error count.
 *
 * <p>Pure + deterministic; compares two {@code path → content} maps.
 */
public final class ReferenceComparisonScorer {

    private static final Pattern PUBSUB_RESIDUE = Pattern.compile(
            "com\\.google\\.[\\w.]*pubsub|com\\.google\\.api\\.services\\.pubsub|\\bPubsub\\b|@PubSubListener");
    private static final Pattern KAFKA_IDIOM = Pattern.compile(
            "org\\.apache\\.kafka\\.|org\\.springframework\\.kafka\\.");

    private ReferenceComparisonScorer() {}

    public static MigrationQualityReport score(Map<String, String> migrated, Map<String, String> reference) {
        List<String> migJava = javaPaths(migrated);
        List<String> refJava = javaPaths(reference);

        List<String> residue = new ArrayList<>();
        int kafkaIdiom = 0;
        for (String p : migJava) {
            String c = migrated.get(p);
            if (c == null) continue;
            if (PUBSUB_RESIDUE.matcher(c).find()) residue.add(p);
            if (KAFKA_IDIOM.matcher(c).find()) kafkaIdiom++;
        }

        Set<String> migBase = baseNames(migJava);
        Set<String> refBase = baseNames(refJava);
        Set<String> extra = new TreeSet<>(migBase);   extra.removeAll(refBase);   // in migration, not in reference
        Set<String> missing = new TreeSet<>(refBase);  missing.removeAll(migBase); // in reference, not in migration

        int migCount = migJava.size();
        int refCount = refJava.size();

        // Sub-scores in [0,1].
        double cleanliness = migCount == 0 ? 0 : 1.0 - (double) residue.size() / migCount;
        double adoption    = migCount == 0 ? 0 : (double) kafkaIdiom / migCount;
        double structural  = refCount == 0 ? 0
                : Math.max(0, 1.0 - (double) Math.abs(migCount - refCount) / refCount);

        double overall = 100.0 * (0.40 * cleanliness + 0.30 * structural + 0.30 * adoption);

        return new MigrationQualityReport(
                migCount, refCount, residue, kafkaIdiom,
                new ArrayList<>(extra), new ArrayList<>(missing),
                round(cleanliness), round(structural), round(adoption), round(overall / 100.0) * 100);
    }

    private static List<String> javaPaths(Map<String, String> files) {
        List<String> out = new ArrayList<>();
        if (files == null) return out;
        for (String p : files.keySet()) {
            if (p != null && p.toLowerCase().endsWith(".java")) out.add(p);
        }
        return out;
    }

    private static Set<String> baseNames(List<String> paths) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : paths) {
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            out.add(p.substring(slash + 1));
        }
        return out;
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Scored comparison of a migration output against the golden reference. */
    public record MigrationQualityReport(
            int migratedFileCount,
            int referenceFileCount,
            List<String> pubsubResidueFiles,
            int kafkaIdiomFileCount,
            List<String> extraFiles,
            List<String> missingFiles,
            double cleanlinessScore,
            double structuralScore,
            double adoptionScore,
            double overallScore
    ) implements Serializable {
        public MigrationQualityReport {
            pubsubResidueFiles = pubsubResidueFiles == null ? List.of() : List.copyOf(pubsubResidueFiles);
            extraFiles         = extraFiles         == null ? List.of() : List.copyOf(extraFiles);
            missingFiles       = missingFiles       == null ? List.of() : List.copyOf(missingFiles);
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Migration quality vs golden reference ===\n");
            sb.append(String.format("Overall: %.1f / 100  (cleanliness=%.2f structural=%.2f adoption=%.2f)%n",
                    overallScore, cleanlinessScore, structuralScore, adoptionScore));
            sb.append(String.format("Files: migrated=%d, reference=%d (delta %+d)%n",
                    migratedFileCount, referenceFileCount, migratedFileCount - referenceFileCount));
            sb.append(String.format("Pub/Sub residue: %d file(s)%s%n", pubsubResidueFiles.size(),
                    pubsubResidueFiles.isEmpty() ? "" : " → " + pubsubResidueFiles));
            sb.append(String.format("Kafka idiom: %d/%d file(s)%n", kafkaIdiomFileCount, migratedFileCount));
            sb.append(String.format("Obsolete-retained (not in reference): %d → %s%n", extraFiles.size(), extraFiles));
            sb.append(String.format("Missing (in reference, absent here): %d → %s%n", missingFiles.size(), missingFiles));
            return sb.toString();
        }
    }
}
