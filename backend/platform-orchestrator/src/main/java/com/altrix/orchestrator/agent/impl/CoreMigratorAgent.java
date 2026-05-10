package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.infrastructure.ai.ContextPruner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 3 — Core Migrator (typed pipeline variant).
 *
 * <p>Reads source files from MinIO via the {@code storageKey} stored in
 * the approved plan, prunes the file set via {@link ContextPruner} (#27),
 * then rewrites each Java file that contains Pub/Sub code using the
 * powerful AI model. Non-Java files and files without Pub/Sub code are
 * passed through unchanged.
 *
 * <p>When a {@code retryContext} is present in the approved plan (#48) it is
 * prepended to the system prompt so the model can fix previously detected issues.
 *
 * <p>Per-file AI failures are non-fatal: the original file is kept and the
 * migration summary notes the skip, so the pipeline completes even when the
 * AI is intermittently unavailable.
 */
@Slf4j
@Component("typedCoreMigratorAgent")
@RequiredArgsConstructor
public class CoreMigratorAgent implements MigrationAgent<ApprovedPlan, MigrationArtifact> {

    private static final String SYSTEM_PROMPT = """
            You are a Java migration expert. Rewrite the following Java source file to migrate from
            Google Cloud Pub/Sub to Apache Kafka (Spring Kafka).

            Rules:
            - Preserve ALL business logic exactly.
            - Replace @SubscriberHandler / @PubSubListener with @KafkaListener.
            - Replace PubSubTemplate / MessagePublisher with KafkaTemplate<String, String>.
            - Update imports: remove google.cloud.pubsub, add org.springframework.kafka.
            - Preserve package declarations, class names, and method signatures.
            - If the file contains no Pub/Sub code, return it exactly as provided.

            Return ONLY the complete rewritten Java file content. No explanations.
            """;

    private final AiPort aiPort;
    private final FileReaderPort fileReader;
    private final ContextPruner contextPruner;

    @Override
    public String getName() {
        return "Core Migrator";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public MigrationArtifact execute(ApprovedPlan input) {
        if (input == null) throw new AgentFailureException(getName(), "input ApprovedPlan was null");

        String projectId = input.plan().projectId();
        String storageKey = input.plan().storageKey();
        log.info("[{}] migrating project '{}' (approved by '{}'{})",
                getName(), projectId, input.approvedBy(),
                input.retryContext() != null ? ", retry" : "");

        if (storageKey == null || storageKey.isBlank()) {
            log.warn("[{}] no storageKey in plan — returning empty artifact", getName());
            return new MigrationArtifact(projectId, List.of(), "No files to migrate (storageKey missing)");
        }

        String effectiveSystemPrompt = input.retryContext() != null && !input.retryContext().isBlank()
                ? input.retryContext() + "\n\n" + SYSTEM_PROMPT
                : SYSTEM_PROMPT;

        try {
            Map<String, String> allFiles = fileReader.readSourceFiles(storageKey);
            PrunedContext pruned = contextPruner.prune(allFiles, input.plan());
            List<MigratedFile> migrated = migrateFiles(pruned.files(), effectiveSystemPrompt);

            // Include unchanged versions of files excluded by the pruner
            List<MigratedFile> result = new ArrayList<>(migrated);
            for (Map.Entry<String, String> entry : allFiles.entrySet()) {
                if (!pruned.files().containsKey(entry.getKey())) {
                    result.add(unchanged(entry.getKey(), entry.getValue(), "Excluded by context pruner"));
                }
            }

            long modifiedCount = result.stream()
                    .filter(f -> f.changeType() == FileChangeType.MODIFIED).count();
            String summary = "Migrated %d/%d file(s) for project '%s' (pruned %d file(s))"
                    .formatted(modifiedCount, result.size(), projectId, pruned.prunedFiles());
            return new MigrationArtifact(projectId, result, summary);
        } catch (Exception e) {
            log.error("[{}] migration failed for project '{}': {}", getName(), projectId, e.getMessage());
            throw new AgentFailureException(getName(), "file migration failed: " + e.getMessage());
        }
    }

    private List<MigratedFile> migrateFiles(Map<String, String> sourceFiles, String systemPrompt) {
        List<MigratedFile> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourceFiles.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();

            if (!path.endsWith(".java")) {
                result.add(unchanged(path, content, "Not a Java source file"));
                continue;
            }
            if (!hasPubSubCode(content)) {
                result.add(unchanged(path, content, "No Pub/Sub code detected"));
                continue;
            }

            try {
                String migrated = aiPort.chat(systemPrompt, "File: " + path + "\n\n" + content);
                FileChangeType changeType = migrated.equals(content)
                        ? FileChangeType.UNCHANGED : FileChangeType.MODIFIED;
                result.add(MigratedFile.builder()
                        .originalPath(path).newPath(path).content(migrated)
                        .changeType(changeType).diffSummary("Migrated Pub/Sub → Kafka")
                        .build());
            } catch (Exception e) {
                log.warn("[{}] AI failed for '{}', keeping original: {}", getName(), path, e.getMessage());
                result.add(unchanged(path, content, "Migration skipped — AI unavailable"));
            }
        }
        return result;
    }

    private static MigratedFile unchanged(String path, String content, String reason) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.UNCHANGED).diffSummary(reason)
                .build();
    }

    private static boolean hasPubSubCode(String content) {
        return content.contains("google.cloud.pubsub")
                || content.contains("PubSubTemplate")
                || content.contains("SubscriberHandler")
                || content.contains("PubSubListener")
                || content.contains("MessagePublisher");
    }
}
