package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 4 — Sandbox Validator.
 *
 * <p>Performs static analysis on every MODIFIED file in the artifact:
 * <ul>
 *   <li>Checks that Pub/Sub imports and annotations have been removed.</li>
 *   <li>Checks that migrated files are non-empty.</li>
 * </ul>
 *
 * <p>Full sandbox compile/run (issues #16/#17) is deferred — this layer
 * catches common migration mistakes without a JVM spin-up.
 */
@Slf4j
@Component("sandboxValidatorAgent")
public class SandboxValidatorAgent implements MigrationAgent<MigrationArtifact, ValidationReport> {

    @Override public String getName()  { return "Sandbox Validator"; }
    @Override public int    getOrder() { return 4; }

    @Override
    public ValidationReport execute(MigrationArtifact input) {
        if (input == null) throw new AgentFailureException(getName(), "input MigrationArtifact was null");
        log.info("[{}] validating {} file(s) for project '{}'",
                getName(), input.files().size(), input.projectId());

        List<String> failures = new ArrayList<>();

        for (var file : input.files()) {
            if (file.changeType() != FileChangeType.MODIFIED) continue;

            String path    = file.newPath();
            String content = file.content();

            if (content == null || content.isBlank()) {
                failures.add(path + ": migrated file is empty");
                continue;
            }
            if (content.contains("google.cloud.pubsub")) {
                failures.add(path + ": Pub/Sub import not removed after migration");
            }
            if (content.contains("@SubscriberHandler") || content.contains("@PubSubListener")) {
                failures.add(path + ": Pub/Sub annotation still present after migration");
            }
        }

        boolean passed = failures.isEmpty();
        String summary = passed
                ? "Validated %d file(s) — all static checks passed".formatted(input.files().size())
                : "Validated %d file(s) — %d issue(s) found".formatted(input.files().size(), failures.size());

        log.info("[{}] validation {}: {}", getName(), passed ? "PASSED" : "FAILED", summary);
        return new ValidationReport(input.projectId(), passed, failures, summary);
    }
}
