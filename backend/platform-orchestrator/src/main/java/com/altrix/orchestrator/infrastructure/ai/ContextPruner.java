package com.altrix.orchestrator.infrastructure.ai;

import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prunes the full source-file map to only the files Agent 3 (CoreMigratorAgent)
 * actually needs to rewrite (#27).
 *
 * <p>Pruning strategy (in order):
 * <ol>
 *   <li><b>Plan-guided + static safety net</b>: if {@link MigrationPlan#targetFiles()}
 *       is non-empty, keep the UNION of (a) those paths and (b) every file whose
 *       content matches {@link PubSubDetector#hasPubSubCode(String)}.  The planner
 *       does not see the file inventory, so its list often under-samples large
 *       projects (e.g. it might name PubsubConfig.java + pom.xml but miss
 *       PubsubService, PubsubClient, PubsubTopic, ...).  Static detection catches
 *       those.  False positives are cheap — the migrator's own per-file check
 *       still skips files without real Pub/Sub usage.</li>
 *   <li><b>Pass-through fallback</b>: if the plan carries no target files, return
 *       the full set — the migrator's per-file filter handles individual decisions.</li>
 * </ol>
 *
 * <p>Token budget enforcement: if the retained files exceed {@code ai.max-context-tokens}
 * the pruner logs a warning and truncates to fit. Batching across multiple Agent 3 passes
 * is a future enhancement.
 */
@Slf4j
@Component
public class ContextPruner {

    private final int maxContextTokens;
    private final Encoding encoding;

    public ContextPruner(
            @Value("${ai.max-context-tokens:32000}") int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncodingForModel(ModelType.GPT_4);
    }

    /**
     * Prunes {@code allFiles} using the migration plan's {@code targetFiles} list.
     *
     * @param allFiles complete map of path → content read from MinIO
     * @param plan     the approved migration plan; may have an empty {@code targetFiles} list
     * @return a {@link PrunedContext} with the filtered file map and pruning metrics
     */
    public PrunedContext prune(Map<String, String> allFiles, MigrationPlan plan) {
        if (allFiles == null || allFiles.isEmpty()) {
            return new PrunedContext(Map.of(), 0, 0);
        }

        int totalFiles = allFiles.size();
        List<String> targetFiles = plan != null ? plan.targetFiles() : List.of();

        Map<String, String> retained;
        if (targetFiles.isEmpty()) {
            log.debug("[ContextPruner] no targetFiles in plan — passing all {} file(s) to migrator", totalFiles);
            retained = allFiles;
        } else {
            // Plan + static safety net: the planner has no file inventory, so its
            // targetFiles list often misses real Pub/Sub files.  Union it with
            // every file that statically looks Pub/Sub-related.
            Set<String> allowlist = new LinkedHashSet<>(targetFiles);
            int planNamed = allowlist.size();
            int staticAdded = 0;
            for (Map.Entry<String, String> entry : allFiles.entrySet()) {
                if (allowlist.contains(entry.getKey())) continue;
                if (PubSubDetector.isMigratableFile(entry.getKey())
                        && PubSubDetector.hasPubSubCode(entry.getValue())) {
                    allowlist.add(entry.getKey());
                    staticAdded++;
                }
            }
            retained = allFiles.entrySet().stream()
                    .filter(e -> allowlist.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new));
            log.debug("[ContextPruner] plan-guided pruning: kept {}/{} file(s) (plan named {}, static-detector added {}, excluded {})",
                    retained.size(), totalFiles, planNamed, staticAdded, totalFiles - retained.size());
        }

        retained = enforceTokenBudget(retained);
        int prunedFiles = totalFiles - retained.size();
        log.info("[ContextPruner] project='{}' — {}/{} file(s) retained ({:.0f}% pruned)",
                plan != null ? plan.projectId() : "?",
                retained.size(), totalFiles, prunedFiles * 100.0 / totalFiles);

        return new PrunedContext(retained, totalFiles, prunedFiles);
    }

    private Map<String, String> enforceTokenBudget(Map<String, String> files) {
        int totalTokens = files.values().stream()
                .mapToInt(encoding::countTokens)
                .sum();

        if (totalTokens <= maxContextTokens) {
            return files;
        }

        log.warn("[ContextPruner] token count {} exceeds budget {}; truncating file set",
                totalTokens, maxContextTokens);

        Map<String, String> budgeted = new LinkedHashMap<>();
        int remaining = maxContextTokens;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            int cost = encoding.countTokens(entry.getValue());
            if (cost > remaining) break;
            budgeted.put(entry.getKey(), entry.getValue());
            remaining -= cost;
        }
        return budgeted;
    }
}
