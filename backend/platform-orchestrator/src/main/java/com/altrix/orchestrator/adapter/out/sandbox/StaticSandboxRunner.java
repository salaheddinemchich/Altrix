package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.domain.port.out.SandboxRunnerPort;
import com.altrix.orchestrator.infrastructure.ai.PubSubDetector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Cheap static checks over the in-memory {@link MigrationArtifact} —
 * extracted from the original {@code SandboxValidatorAgent} body so it
 * can coexist with the Docker / Checkstyle / SpotBugs runners landing
 * in #16, #17, #97.
 *
 * <p>Scope deliberately narrow: only flags <b>regressions</b> that the
 * migrator should never let through (empty migrated file, Pub/Sub
 * imports / annotations not stripped).  Anything that needs to actually
 * compile or execute the code lives in a different runner.
 *
 * <p>Pure CPU work; no IO, no network — order 0 so it always runs first
 * and short-circuits cheap mistakes before heavier runners spin up.
 */
@Component
public class StaticSandboxRunner implements SandboxRunnerPort {

    public static final String ID = "static";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public List<SandboxFinding> run(MigrationArtifact artifact) {
        if (artifact == null || artifact.files() == null) return List.of();

        List<SandboxFinding> findings = new ArrayList<>();
        for (var file : artifact.files()) {
            if (file.changeType() != FileChangeType.MODIFIED) continue;

            String path = file.newPath();
            String content = file.content();

            if (content == null || content.isBlank()) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "migrated file is empty"));
                continue;
            }

            // Modern Spring Cloud GCP imports must be gone.
            if (content.contains("google.cloud.pubsub")) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "Pub/Sub import not removed after migration"));
            }
            if (content.contains("@SubscriberHandler") || content.contains("@PubSubListener")) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "Pub/Sub annotation still present after migration"));
            }

            // Legacy REST v1 should also be gone — the migrator now handles
            // both styles (commit 639e51e), so a leftover here is a
            // real regression.  Only the .java files trip this; build /
            // YAML files legitimately mention google-cloud-pubsub when
            // they were never Pub/Sub-related to begin with.
            if (path.endsWith(".java") && content.contains("com.google.api.services.pubsub")) {
                findings.add(SandboxFinding.ofFile(ID, Severity.ERROR, path,
                        "Legacy GCP Pub/Sub REST v1 import not removed"));
            }
        }

        // Project-level sanity: at least ONE file should have been modified
        // when the planner identified migration targets.  An all-untouched
        // result means the migrator silently no-op'd — worth flagging.
        boolean anyModified = artifact.files().stream()
                .anyMatch(f -> f.changeType() == FileChangeType.MODIFIED);
        if (!anyModified && !artifact.files().isEmpty()) {
            findings.add(SandboxFinding.of(ID, Severity.WARNING,
                    "No files were modified by the migrator — verify the plan's targetFiles list "
                    + "and ensure the static detector caught the Pub/Sub references " + PubSubDetector.class.getSimpleName()));
        }

        return findings;
    }
}
