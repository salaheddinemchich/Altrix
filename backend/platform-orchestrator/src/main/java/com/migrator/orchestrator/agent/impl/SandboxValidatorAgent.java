package com.migrator.orchestrator.agent.impl;

import com.migrator.common.domain.model.MigrationArtifact;
import com.migrator.common.domain.model.ValidationReport;
import com.migrator.common.domain.port.MigrationAgent;
import com.migrator.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 4 — Sandbox Validator.
 *
 * <p>Identity-pass stub: marks the artifact as validated without actually
 * compiling or running it. Real sandboxed compile + integration test
 * execution lands in issues #16/#17.
 */
@Slf4j
@Component("sandboxValidatorAgent")
public class SandboxValidatorAgent implements MigrationAgent<MigrationArtifact, ValidationReport> {

    @Override public String getName() { return "Sandbox Validator"; }

    @Override public int getOrder() { return 4; }

    @Override
    public ValidationReport execute(MigrationArtifact input) {
        if (input == null) {
            throw new AgentFailureException(getName(), "input MigrationArtifact was null");
        }
        log.info("[{}] (stub) skipping sandbox validation for project '{}'",
                getName(), input.projectId());
        return ValidationReport.pending(input.projectId());
    }
}
