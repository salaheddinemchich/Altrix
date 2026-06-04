package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.contract.ContractViolation;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.domain.port.out.SandboxRunnerPort;
import com.altrix.orchestrator.infrastructure.contract.ContractValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defense-in-depth: runs {@link ContractValidator} as a sandbox runner.
 *
 * <p>The {@code CoreMigratorAgent} already attempts an LLM repair loop
 * for contract violations.  When that loop exits with leftover findings
 * (model couldn't see the fix, AI was unavailable, repair budget was
 * exhausted), this runner surfaces them as {@link SandboxFinding}s with
 * ERROR severity so the sandbox-level retry loop can keep iterating
 * instead of letting the Docker compile blow up on the same root cause.
 *
 * <p>Order {@code -10} — runs strictly before the static checker (order 0)
 * and the Docker compiler (order 100+), because contract failures dwarf
 * everything else: a missing interface method makes every dependent file
 * fail to compile and the sandbox log becomes useless noise.
 */
@Component
@RequiredArgsConstructor
public class ContractSandboxRunner implements SandboxRunnerPort {

    public static final String ID = "contract";

    private final ContractValidator validator;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return -10;
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

        List<ContractViolation> violations = validator.validate(files);
        if (violations.isEmpty()) return List.of();

        List<SandboxFinding> findings = new ArrayList<>(violations.size());
        for (ContractViolation v : violations) {
            findings.add(new SandboxFinding(
                    ID,
                    Severity.ERROR,
                    v.filePath(),
                    v.line(),
                    "[" + v.kind().name() + "] "
                            + (v.symbol().isEmpty() ? "" : "'" + v.symbol() + "': ")
                            + v.message()));
        }
        return findings;
    }
}
