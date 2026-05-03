package com.migrator.orchestrator.agent.impl;

import com.migrator.common.domain.model.ApprovedPlan;
import com.migrator.common.domain.model.MigrationArtifact;
import com.migrator.common.domain.model.MigrationPlan;
import com.migrator.common.exception.AgentFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreMigratorAgentTest {

    private final CoreMigratorAgent agent = new CoreMigratorAgent();

    @Test
    void exposesNameAndOrder3() {
        assertThat(agent.getName()).isEqualTo("Core Migrator");
        assertThat(agent.getOrder()).isEqualTo(3);
    }

    @Test
    void execute_returnsArtifactCarryingProjectIdFromPlan() {
        MigrationPlan plan = new MigrationPlan(
                "p1", List.of("step1"), "rewrite messaging layer");
        ApprovedPlan approved = ApprovedPlan.autoApproved(plan);

        MigrationArtifact artifact = agent.execute(approved);

        assertThat(artifact.projectId()).isEqualTo("p1");
        assertThat(artifact.files()).isEmpty(); // stub for now
        assertThat(artifact.summary()).contains("rewrite messaging layer");
    }

    @Test
    void execute_handlesEmptyPlanSummary() {
        ApprovedPlan approved = ApprovedPlan.autoApproved(MigrationPlan.empty("p1"));
        MigrationArtifact artifact = agent.execute(approved);
        assertThat(artifact.summary()).contains("no files generated");
    }

    @Test
    void execute_nullInput_throws() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
