package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.leak.PubSubLeakViolation;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.domain.port.out.SandboxRunnerPort;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defense-in-depth: runs {@link PubSubLeakValidator} as a sandbox runner.
 *
 * <p>The migrator already attempts an LLM repair loop for Pub/Sub leaks.
 * When that loop exits with leftover findings (model couldn't see the
 * fix, AI was unavailable, repair budget exhausted), this runner
 * surfaces them as {@link SandboxFinding}s with ERROR severity so the
 * sandbox-level retry loop can keep iterating instead of letting the
 * Docker compile blow up with "package com.google.api.services.pubsub
 * does not exist" 30 times in a row.
 *
 * <p>Order {@code -5} — runs after the Contract runner ({@code -10})
 * because a contract failure is a more fundamental problem (interface
 * drift makes the file uncompilable regardless of Pub/Sub leaks), and
 * before the Static runner (0) / Docker compile (later).
 */
@Component
@RequiredArgsConstructor
public class PubSubLeakSandboxRunner implements SandboxRunnerPort {

    public static final String ID = "pubsub-leak";

    private final PubSubLeakValidator validator;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return -5;
    }

    @Override
    public List<SandboxFinding> run(MigrationArtifact artifact) {
        if (artifact == null || artifact.files() == null || artifact.files().isEmpty()) {
            return List.of();
        }

        Map<String, String> files = new LinkedHashMap<>();
        for (MigratedFile f : artifact.files()) {
            String path = f.newPath() != null ? f.newPath() : f.originalPath();
            if (path != null && f.content() != null) files.put(path, f.content());
        }

        List<PubSubLeakViolation> violations = validator.validate(files);
        if (violations.isEmpty()) return List.of();

        List<SandboxFinding> findings = new ArrayList<>(violations.size());
        for (PubSubLeakViolation v : violations) {
            findings.add(new SandboxFinding(
                    ID,
                    Severity.ERROR,
                    v.filePath(),
                    v.line(),
                    "[" + v.kind().name() + "] "
                            + (v.symbol().isEmpty() ? "" : "'" + v.symbol() + "': ")
                            + v.reason()
                            + (v.suggestion().isEmpty() ? "" : "  -> " + v.suggestion())));
        }
        return findings;
    }
}
