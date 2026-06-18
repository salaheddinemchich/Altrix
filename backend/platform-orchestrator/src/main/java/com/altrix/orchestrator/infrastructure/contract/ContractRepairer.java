package com.altrix.orchestrator.infrastructure.contract;

import com.altrix.orchestrator.domain.model.contract.ContractViolation;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.infrastructure.migration.JavaOutputGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal-patch repair loop driven by {@link ContractValidator}.
 *
 * <p>Flow per iteration:
 * <ol>
 *   <li>Validate the current file set against the contract index.</li>
 *   <li>If clean, return.</li>
 *   <li>For every file with violations, ask the LLM for a minimal patch
 *       — the original file plus the validator's violation lines.  The
 *       model returns the corrected file body; we do NOT ask it to
 *       redesign anything.</li>
 *   <li>Run the post-processing pipeline already used by {@code CoreMigratorAgent}
 *       (markdown fences, structural sanity) on the result; replace the
 *       file in the working map.</li>
 *   <li>Loop until clean or {@code maxIterations} exhausted or violation
 *       count stops decreasing (no point burning AI calls if we're stuck).</li>
 * </ol>
 *
 * <p>This is the {@code Project Semantic Model} consumer.  Everything we
 * give the model here is concrete and small — we never ask it to "fix the
 * project"; we ask it to fix one named file given a known issue list.
 *
 * <p>Failure modes are non-fatal: a per-file repair call that throws is
 * logged and the original-revised content for that file is kept.  The
 * validator runs again on the next iteration; if the violation is still
 * there the outer loop will stop after detecting "no progress".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractRepairer {

    private final ContractValidator validator;
    private final AiPort aiPort;

    /**
     * How many repair passes to attempt.  Each pass calls the LLM once per
     * violating file.  Cap is conservative — empirically 1-2 iterations
     * fix the vast majority of structural issues; more than 3 means the
     * model can't see the answer and we should let the sandbox loop take over.
     */
    @Value("${migration.contract.max-repair-iterations:3}")
    private int maxIterations;

    /** Max characters of a file's source we put in one prompt.  Files larger than this are skipped (the repair won't help). */
    @Value("${migration.contract.max-file-chars:24000}")
    private int maxFileChars;

    /**
     * Attempt to repair all contract violations in {@code workingSet}.
     * Returns the (possibly partially-repaired) working set — callers can
     * compare {@code validator.validate(result)} to know whether the
     * artifact is now clean.
     *
     * <p>{@code workingSet} is taken by reference (a {@link LinkedHashMap}
     * is returned so the caller's file ordering is preserved) but never
     * mutated.
     */
    public Map<String, String> repair(Map<String, String> workingSet) {
        if (workingSet == null || workingSet.isEmpty()) return workingSet;
        Map<String, String> current = new LinkedHashMap<>(workingSet);

        int previousCount = Integer.MAX_VALUE;
        for (int i = 0; i < maxIterations; i++) {
            List<ContractViolation> violations = validator.validate(current);
            if (violations.isEmpty()) {
                log.info("[ContractRepairer] iteration {}/{}: clean — stopping", i, maxIterations);
                return current;
            }
            if (violations.size() >= previousCount) {
                log.warn("[ContractRepairer] iteration {}/{}: {} violation(s), no progress — stopping",
                        i, maxIterations, violations.size());
                return current;
            }
            previousCount = violations.size();

            Map<String, List<ContractViolation>> byFile = validator.groupByFile(violations);
            log.info("[ContractRepairer] iteration {}/{}: {} violation(s) across {} file(s)",
                    i + 1, maxIterations, violations.size(), byFile.size());

            for (Map.Entry<String, List<ContractViolation>> entry : byFile.entrySet()) {
                String path = entry.getKey();
                String original = current.get(path);
                if (original == null) continue;
                if (original.length() > maxFileChars) {
                    log.debug("[ContractRepairer] skipping '{}' — file too large for repair prompt ({} chars)",
                            path, original.length());
                    continue;
                }
                String patched = repairOneFile(path, original, entry.getValue());
                if (patched != null && !patched.isBlank() && !patched.equals(original)) {
                    current.put(path, patched);
                }
            }
        }

        // Final-pass validation before returning — informational only; the
        // caller decides what to do with leftover violations.
        List<ContractViolation> remaining = validator.validate(current);
        if (!remaining.isEmpty()) {
            log.warn("[ContractRepairer] exhausted {} iteration(s) — {} violation(s) remain",
                    maxIterations, remaining.size());
        }
        return current;
    }

    /**
     * Asks the LLM for a minimal patch of one file.  Uses the regular chat
     * channel (not the fast one) — repairs are precision work, not bulk
     * transformation, and the cost difference at one-file granularity is
     * negligible.
     *
     * @return the repaired file content, or {@code null} if the call
     *         failed or produced obviously invalid output.
     */
    private String repairOneFile(String path, String content, List<ContractViolation> violations) {
        String violationList = violations.stream()
                .map(ContractViolation::toLine)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

        String systemPrompt = """
                You are repairing one Java file in an automated migration that has just
                been flagged by a static contract validator.  The validator parses the
                whole project and knows which types / methods exist in the artifact —
                trust its findings literally.

                RULES — violating any guarantees a broken build:
                  * Output the COMPLETE corrected file content and NOTHING else.
                    No prose, no markdown fences, no explanations.
                  * Make the SMALLEST POSSIBLE change that fixes every reported
                    violation.  Do not refactor, rename, or "tidy" anything that
                    wasn't flagged.
                  * Preserve the file's package declaration, public type name,
                    Javadoc, and unrelated imports exactly.
                  * If a violation says "no method X" — either fix the call site
                    (preferred) or restore the method on the declaring type, do
                    NOT invent a new placeholder type.
                  * If a violation says "@Override but no supertype declares it"
                    — remove the @Override (or fix the signature so it matches).
                  * If a violation says "missing interface method" — add a minimal
                    implementation: signature matches the interface, body either
                    delegates sensibly or throws UnsupportedOperationException with
                    a comment explaining the migration TODO.
                  * If a violation says "does not match the interface contract.
                    Required EXACT signature: ..." — the interface is the CONTRACT.
                    Rewrite THIS method's parameter and return types to match the
                    required signature EXACTLY (character for character), and adjust
                    the body to the new types (e.g. if a `PubsubTopic topic` param
                    became `String topic`, use the String directly instead of
                    `topic.getName()`). Do NOT change the interface, do NOT add an
                    overload — change the existing method in place so it overrides.
                  * If a violation says "file/class mismatch" — rename the public
                    type back to match the filename.  The filename cannot change.
                  * If a violation says "imported but no type" — drop the bogus
                    import (the model invented it) and leave the rest of the file
                    intact.
                  * If a violation says "referenced but is not imported and is not
                    declared anywhere" — first try a rename: re-point EVERY use of
                    the dangling name in this file to the correct existing type from
                    the suggested list when one is a clear match (e.g.
                    AltrixKafkaMessage -> AltrixPubsubMessage). Do NOT invent a new
                    class and do NOT add an import for a type that does not exist.
                    If NO listed type is a sensible match, the name is an obsolete
                    SOURCE-PLATFORM concept with no target equivalent (e.g. a Pub/Sub
                    `Subscription` or `Topic` management type). In that case replace
                    its uses with `Object` (or remove the member entirely) — be
                    CONSISTENT with how sibling no-equivalent methods in the same file
                    were already handled (if `getOrCreateTopic` returns `Object`, make
                    `getOrCreateSubscription` return `Object` too). Apply the exact
                    same change to the interface AND its implementation so they stay
                    in sync. This `-> Object` rule applies ONLY to return/parameter/field
                    types — NEVER to an `implements`/`extends` clause. `implements Object`
                    is a compile error; if an implemented interface is obsolete, drop the
                    `implements` clause entirely, do not replace it with Object.
                  * If a violation says "used but not imported; it is declared in
                    package X" — add EXACTLY the import line it names (e.g.
                    `import com.example.altrix.pubsub.RetryTask;`) and change nothing
                    else. Do not move, rename, or redefine the type.
                  * If a violation says "constructor arity" — fix the new-expression
                    arguments OR add a constructor overload of that arity.  Do
                    not change the existing constructor signature.

                You will receive the file body and a list of violations.  Reply
                ONLY with the new file body.
                """;

        String userPrompt = "File: " + path + "\n\nViolations:\n" + violationList
                + "\n\nFile body:\n" + content;

        try {
            String raw = aiPort.chat(systemPrompt, userPrompt);
            if (raw == null || raw.isBlank()) return null;
            String cleaned = stripFences(raw).strip();
            // Sanity — must start with `package` or an import / comment, and
            // must still declare a type.  Lower the bar here on purpose:
            // exotic repairs (records without package) are legal too.
            if (!cleaned.contains("class ")
                    && !cleaned.contains("interface ")
                    && !cleaned.contains("enum ")
                    && !cleaned.contains("record ")
                    && !cleaned.contains("@interface ")) {
                return null;
            }
            // Reject truncated or corrupted output (model hit its token limit
            // mid-method, or glitched mid-generation) — a patch that doesn't
            // parse is strictly worse than no patch, since it still gets
            // written into the artifact.
            if (path.endsWith(".java") && JavaOutputGuard.isMalformed(cleaned)) {
                log.warn("[ContractRepairer] repair output for '{}' does not parse as valid Java — discarding", path);
                return null;
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("[ContractRepairer] AI call failed for '{}': {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Lightweight de-fencing for repair output.  We accept either a raw file
     * body or a fenced block.  Different from {@code CoreMigratorAgent}'s
     * stripper because the repair system prompt is much stricter — we
     * forbid prose explicitly — so we don't need the three-shape gymnastics.
     */
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
