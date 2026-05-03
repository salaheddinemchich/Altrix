package com.migrator.orchestrator.agent.impl;

import com.migrator.common.domain.model.MigrationArtifact;
import com.migrator.common.domain.model.ValidationReport;
import com.migrator.common.exception.AgentFailureException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxValidatorAgentTest {

    private final SandboxValidatorAgent agent = new SandboxValidatorAgent();

    @Test
    void exposesNameAndOrder4() {
        assertThat(agent.getName()).isEqualTo("Sandbox Validator");
        assertThat(agent.getOrder()).isEqualTo(4);
    }

    @Test
    void stub_marksValidationAsPassed() {
        ValidationReport report = agent.execute(MigrationArtifact.empty("p1"));
        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isEmpty();
        assertThat(report.summary()).contains("stub");
    }

    @Test
    void execute_nullInput_throws() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
