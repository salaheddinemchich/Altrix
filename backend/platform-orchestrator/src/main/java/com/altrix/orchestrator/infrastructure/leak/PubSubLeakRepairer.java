package com.altrix.orchestrator.infrastructure.leak;

import com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation;
import com.altrix.orchestrator.domain.port.out.AiPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal-patch repair loop for Pub/Sub leaks.
 *
 * <p>Runs AFTER {@code ContractRepairer} so the artifact has stable
 * interfaces and method signatures by the time we attempt to swap
 * Google Pub/Sub calls for Kafka.  Each iteration:
 * <ol>
 *   <li>Validate via {@link PubSubLeakValidator}.</li>
 *   <li>Group violations by file path.</li>
 *   <li>Per file, build a kind-aware repair prompt — the prompt includes
 *       the file body, the list of violations, AND the validator's
 *       pre-computed Kafka-replacement suggestion per leak.  The model
 *       has no design freedom: we are telling it exactly which Kafka
 *       call to use.</li>
 *   <li>Apply the AI's response to the working set.</li>
 *   <li>Loop until clean, until {@code maxIterations} exhausted, or
 *       until the violation count stops decreasing.</li>
 * </ol>
 *
 * <p>The repair always operates on one file at a time.  Multi-file
 * cross-references are NOT in scope here — they belong to the Contract
 * Repair pass (the layer above) and are already stable by the time we
 * run.
 *
 * <p>Failure modes are non-fatal: an AI call that throws / returns
 * obviously invalid output leaves the file unchanged.  The validator
 * sees the same violation again on the next iteration, and after one
 * more no-progress round the loop terminates so we don't burn the AI
 * budget on a stuck file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PubSubLeakRepairer {

    private final PubSubLeakValidator validator;
    private final AiPort aiPort;

    /** Bounded repair budget. */
    @Value("${migration.leak.max-repair-iterations:3}")
    private int maxIterations;

    /** Files larger than this are skipped — the LLM will not respond well to multi-thousand-line surgical edits. */
    @Value("${migration.leak.max-file-chars:24000}")
    private int maxFileChars;

    public Map<String, String> repair(Map<String, String> workingSet) {
        if (workingSet == null || workingSet.isEmpty()) return workingSet;
        Map<String, String> current = new LinkedHashMap<>(workingSet);

        int previousCount = Integer.MAX_VALUE;
        for (int i = 0; i < maxIterations; i++) {
            List<PubSubLeakViolation> violations = validator.validate(current);
            if (violations.isEmpty()) {
                log.info("[PubSubLeakRepairer] iteration {}/{}: clean — stopping", i, maxIterations);
                return current;
            }
            if (violations.size() >= previousCount) {
                log.warn("[PubSubLeakRepairer] iteration {}/{}: {} violation(s), no progress — stopping",
                        i, maxIterations, violations.size());
                return current;
            }
            previousCount = violations.size();

            Map<String, List<PubSubLeakViolation>> byFile = validator.groupByFile(violations);
            log.info("[PubSubLeakRepairer] iteration {}/{}: {} violation(s) across {} file(s)",
                    i + 1, maxIterations, violations.size(), byFile.size());

            for (Map.Entry<String, List<PubSubLeakViolation>> entry : byFile.entrySet()) {
                String path = entry.getKey();
                String original = current.get(path);
                if (original == null) continue;
                if (original.length() > maxFileChars) {
                    log.debug("[PubSubLeakRepairer] skipping '{}' — file too large ({} chars)", path, original.length());
                    continue;
                }
                String patched = repairOneFile(path, original, entry.getValue());
                if (patched != null && !patched.isBlank() && !patched.equals(original)) {
                    current.put(path, patched);
                }
            }
        }

        List<PubSubLeakViolation> remaining = validator.validate(current);
        if (!remaining.isEmpty()) {
            log.warn("[PubSubLeakRepairer] exhausted {} iteration(s) — {} leak(s) remain",
                    maxIterations, remaining.size());
        }
        return current;
    }

    /**
     * Ask the LLM for a Pub/Sub-free rewrite of one file.  The prompt is
     * deliberately surgical: every Kafka-replacement strategy comes from
     * the validator's pre-computed suggestion field so the model has zero
     * design freedom and zero need to "guess" the migration approach.
     */
    private String repairOneFile(String path, String content, List<PubSubLeakViolation> violations) {
        String violationList = violations.stream()
                .map(PubSubLeakViolation::toLine)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

        String systemPrompt = """
                You are a precision migration assistant.  The target is Apache Kafka.
                A static scanner has detected that the file below still contains Google
                Cloud Pub/Sub artifacts — these MUST be removed.

                RULES — violating any guarantees a broken build:
                  * Output the COMPLETE corrected file content and NOTHING else.
                    No prose, no markdown fences, no explanations.
                  * Make the SMALLEST POSSIBLE change.  Do not refactor or rename
                    anything that wasn't flagged.
                  * Preserve the file's package declaration, public type name, and
                    every non-Pub/Sub-related import / annotation / Javadoc.
                  * Every Google Pub/Sub artifact must be GONE from the output:
                      - no `import com.google.api.services.pubsub.*`
                      - no `import com.google.cloud.pubsub.*`
                      - no `import com.google.pubsub.*`
                      - no `import org.springframework.cloud.gcp.pubsub.*`
                      - no references to `Pubsub`, `PubsubMessage`, `ReceivedMessage`,
                        `PublishRequest`, `PublishResponse`, `PullRequest`,
                        `PullResponse`, `AcknowledgeRequest`, `PubSubTemplate`,
                        `Subscriber`, `Publisher`, `TopicAdminClient`,
                        `SubscriptionAdminClient` as type names
                      - no `*.projects().topics().*`, `*.projects().subscriptions().*`,
                        `testIamPermissions(...)` method chains
                  * Use the EXACT Kafka replacement strategy each violation specifies
                    in its ` -> ` suggestion.  Do not invent alternative approaches.
                  * If a violation has no clean Kafka equivalent (IAM permission
                    testing, ack-id semantics, ordering keys), keep the surrounding
                    method shape and put a `// TODO altrix:` comment inside the
                    method body explaining what manual work is needed.
                  * KafkaProducer / KafkaConsumer / ProducerRecord / ConsumerRecord
                    / ConsumerRecords / OffsetAndMetadata / TopicPartition /
                    Duration — when you reference any of these, you MUST add the
                    matching import.
                  * Do not introduce Spring annotations on a Jakarta EE project
                    (no `@KafkaListener`, no `KafkaTemplate`, no `@Component`).
                    Use raw kafka-clients with the existing CDI / EJB lifecycle.

                You will receive the file body, the list of leaks, and a Kafka
                suggestion for each leak.  Reply ONLY with the new file body.
                """;

        String userPrompt = "File: " + path + "\n\nPub/Sub leaks:\n" + violationList
                + "\n\nFile body:\n" + content;

        try {
            String raw = aiPort.chat(systemPrompt, userPrompt);
            if (raw == null || raw.isBlank()) return null;
            String cleaned = stripFences(raw).strip();
            if (!cleaned.contains("class ")
                    && !cleaned.contains("interface ")
                    && !cleaned.contains("enum ")
                    && !cleaned.contains("record ")
                    && !cleaned.contains("@interface ")) {
                return null;
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("[PubSubLeakRepairer] AI call failed for '{}': {}", path, e.getMessage());
            return null;
        }
    }

    private static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl < 0) return "";
            s = s.substring(firstNl + 1);
            int close = s.lastIndexOf("```");
            if (close >= 0) s = s.substring(0, close);
        }
        return s.stripTrailing();
    }
}
