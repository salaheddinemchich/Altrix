package com.migrator.orchestrator.agent.impl;

import com.migrator.common.domain.model.ApprovedPlan;
import com.migrator.common.domain.model.MigrationArtifact;
import com.migrator.common.domain.port.MigrationAgent;
import com.migrator.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 3 — Core Migrator (typed pipeline variant).
 *
 * <p>Consumes an {@link ApprovedPlan} and produces a {@link MigrationArtifact}
 * containing the rewritten files. Until issue #35 wires the typed pipeline,
 * this class coexists with the older
 * {@code adapter.out.agent.CoreMigratorAgent} which still runs the
 * {@code ProjectContext}-based path — hence the explicit Spring bean name to
 * avoid collisions.
 */
@Slf4j
@Component("typedCoreMigratorAgent")
public class CoreMigratorAgent implements MigrationAgent<ApprovedPlan, MigrationArtifact> {

    @Override public String getName() { return "Core Migrator"; }

    @Override public int getOrder() { return 3; }

    @Override
    public MigrationArtifact execute(ApprovedPlan input) {
        if (input == null) {
            throw new AgentFailureException(getName(), "input ApprovedPlan was null");
        }
        String projectId = input.plan().projectId();
        log.info("[{}] migrating project '{}' (plan approved by '{}')",
                getName(), projectId, input.approvedBy());

        // Real migration logic lands when #35 retires the legacy AgentPort path.
        // For now: return an empty artifact carrying the plan's summary so
        // downstream stub agents have a stable shape to operate on.
        String summary = input.plan().summary().isBlank()
                ? "Artifact stub — no files generated"
                : "Artifact stub — plan: " + input.plan().summary();

        return new MigrationArtifact(projectId, List.of(), summary);
    }
}
