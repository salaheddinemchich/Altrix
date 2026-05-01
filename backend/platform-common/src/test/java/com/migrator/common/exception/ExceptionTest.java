package com.migrator.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionTest {

    @Test
    void jobNotFoundException_hasCorrectMessageAndCode() {
        JobNotFoundException ex = new JobNotFoundException("job-123");

        assertThat(ex.getMessage()).contains("job-123");
        assertThat(ex.getErrorCode()).isEqualTo("JOB_NOT_FOUND");
    }

    @Test
    void projectNotFoundException_hasCorrectMessageAndCode() {
        ProjectNotFoundException ex = new ProjectNotFoundException("proj-456");

        assertThat(ex.getMessage()).contains("proj-456");
        assertThat(ex.getErrorCode()).isEqualTo("PROJECT_NOT_FOUND");
    }

    @Test
    void agentFailureException_includesAgentNameAndReason() {
        AgentFailureException ex = new AgentFailureException("Architecture Analyzer", "timeout");

        assertThat(ex.getMessage()).contains("Architecture Analyzer");
        assertThat(ex.getMessage()).contains("timeout");
        assertThat(ex.getErrorCode()).isEqualTo("AGENT_FAILURE");
    }

    @Test
    void allExceptions_extendBasePlatformException() {
        assertThat(new JobNotFoundException("x")).isInstanceOf(BasePlatformException.class);
        assertThat(new ProjectNotFoundException("x")).isInstanceOf(BasePlatformException.class);
        assertThat(new AgentFailureException("x", "y")).isInstanceOf(BasePlatformException.class);
    }
}
