package com.altrix.orchestrator.infrastructure.blueprint;

import com.altrix.orchestrator.domain.model.blueprint.BlueprintFeature;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFeature.Evidence;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects migration-relevant GCP Pub/Sub features in each source file and
 * enriches them with the matching {@link KafkaMigrationKnowledgeBase}
 * mapping — so a {@code BlueprintFeature} carries both WHERE the feature
 * is and HOW it migrates to Kafka.
 *
 * <p>Detection is by source-idiom pattern.  These idioms describe the
 * fixed legacy GCP Pub/Sub REST v1 API surface (not project-specific
 * config), so they live here as built-in rules whose {@code featureId}
 * keys join to the YAML knowledge base for the Kafka target.  The
 * knowledge base remains the single authority for the migration mapping,
 * required imports/dependencies and forbidden symbols; this class only
 * owns "what does feature X look like in source".
 *
 * <p>One {@link BlueprintFeature} is emitted per (file, featureId) — the
 * first matching occurrence supplies the evidence even if the idiom
 * appears several times in the file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureClassifier {

    private final KafkaMigrationKnowledgeBase knowledgeBase;

    /** featureId → the source idioms that signal it.  featureId matches the KB. */
    private static final List<DetectionRule> RULES = List.of(
            rule("pubsub.publish-single",
                    "\\.publish\\s*\\(",
                    "projects\\(\\)\\.topics\\(\\)\\.publish"),
            rule("pubsub.pull",
                    "\\.pull\\s*\\(",
                    "subscriptions\\(\\)\\.pull"),
            rule("pubsub.ack",
                    "\\.acknowledge\\s*\\(",
                    "subscriptions\\(\\)\\.acknowledge"),
            rule("pubsub.get-or-create-topic",
                    "getOrCreateTopic",
                    "\\.topics\\(\\)\\.create",
                    "\\.topics\\(\\)\\.get"),
            rule("pubsub.subscription",
                    "getOrCreateSubscription",
                    "createSubscription",
                    "subscriptions\\(\\)\\.create"),
            rule("pubsub.ordering-key",
                    "setOrderingKey",
                    "getOrderingKey"),
            rule("pubsub.test-iam",
                    "testIAMPermissions",
                    "testIamPermissions")
    );

    /**
     * @param javaSources path → source content (Java files only; callers
     *                    filter before this point).
     * @return path → detected features.  Files with no Pub/Sub idioms are
     *         absent from the map (the blueprint treats them as pass-through).
     */
    public Map<String, List<BlueprintFeature>> classify(Map<String, String> javaSources) {
        Map<String, List<BlueprintFeature>> result = new LinkedHashMap<>();
        if (javaSources == null || javaSources.isEmpty()) return result;

        for (Map.Entry<String, String> e : javaSources.entrySet()) {
            String path = e.getKey();
            String source = e.getValue();
            if (path == null || source == null || !path.toLowerCase().endsWith(".java")) continue;

            List<BlueprintFeature> features = classifyOne(source);
            if (!features.isEmpty()) result.put(path, features);
        }
        log.info("Feature classifier: {} of {} file(s) carry Pub/Sub features",
                result.size(), javaSources.size());
        return result;
    }

    /** Public so a unit test can hit a single file without the map plumbing. */
    public List<BlueprintFeature> classifyOne(String source) {
        String[] lines = source.split("\n", -1);
        List<BlueprintFeature> features = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (DetectionRule r : RULES) {
            for (int i = 0; i < lines.length; i++) {
                if (!r.matches(lines[i])) continue;
                if (!seen.add(r.featureId())) break;     // one feature per id; first match wins
                features.add(buildFeature(r.featureId(), enclosingMethod(lines, i), i + 1, lines[i]));
                break;
            }
        }
        return features;
    }

    private BlueprintFeature buildFeature(String featureId, String method, int line, String rawLine) {
        var mapping = knowledgeBase.findByFeature(featureId);
        String kafkaTarget = mapping.map(KafkaMigrationKnowledgeBase.Mapping::kafkaEquivalent).orElse(null);
        String description = mapping.map(KafkaMigrationKnowledgeBase.Mapping::notes)
                .filter(n -> n != null && !n.isBlank())
                .map(FeatureClassifier::firstSentence)
                .orElse("Detected " + featureId);
        // docRefs left empty here — the doc-linking stage (S2c) resolves
        // which corpus pages back each feature.  kafkaTarget already gives
        // the migrator a concrete instruction.
        return new BlueprintFeature(
                featureId, description,
                new Evidence(method, line, rawLine.strip()),
                kafkaTarget, List.of());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Cheap enclosing-method heuristic: nearest method declaration above the line. */
    private static String enclosingMethod(String[] lines, int idx) {
        Pattern methodDecl = Pattern.compile(
                "\\b(?:public|private|protected|static|final|\\s)*[\\w<>\\[\\],.?]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*\\{?\\s*$");
        for (int i = idx; i >= 0; i--) {
            var m = methodDecl.matcher(lines[i].strip());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String firstSentence(String notes) {
        String trimmed = notes.strip();
        int dot = trimmed.indexOf(". ");
        return dot > 0 ? trimmed.substring(0, dot + 1) : trimmed;
    }

    private static DetectionRule rule(String featureId, String... regexes) {
        List<Pattern> patterns = new ArrayList<>(regexes.length);
        for (String r : regexes) patterns.add(Pattern.compile(r));
        return new DetectionRule(featureId, patterns);
    }

    private record DetectionRule(String featureId, List<Pattern> patterns) {
        boolean matches(String line) {
            for (Pattern p : patterns) if (p.matcher(line).find()) return true;
            return false;
        }
    }
}
