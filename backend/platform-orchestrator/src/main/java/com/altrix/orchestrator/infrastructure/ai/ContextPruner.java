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
 *   <li><b>Plan-guided</b>: if {@link MigrationPlan#targetFiles()} is non-empty, keep
 *       only those paths. This cuts 75–85% of context for typical applications.</li>
 *   <li><b>Pass-through fallback</b>: if the plan carries no target files, return the
 *       full set — the migrator's own {@code hasPubSubCode()} filter handles per-file
 *       decisions.</li>
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
            Set<String> targetSet = Set.copyOf(targetFiles);
            retained = allFiles.entrySet().stream()
                    .filter(e -> targetSet.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new));
            log.debug("[ContextPruner] plan-guided pruning: kept {}/{} file(s), excluded {}",
                    retained.size(), totalFiles, totalFiles - retained.size());
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
