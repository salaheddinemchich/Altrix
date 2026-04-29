package com.migrator.common.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobStatusTest {

    @Test
    void terminalStatuses_areDoneFailedAndCancelled() {
        assertThat(JobStatus.DONE.isTerminal()).isTrue();
        assertThat(JobStatus.FAILED.isTerminal()).isTrue();
        assertThat(JobStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void nonTerminalStatuses_areNotTerminal() {
        assertThat(JobStatus.PENDING.isTerminal()).isFalse();
        assertThat(JobStatus.ANALYZING.isTerminal()).isFalse();
        assertThat(JobStatus.MIGRATING.isTerminal()).isFalse();
    }

    @Test
    void inProgressStatuses_areAnalyzingAndMigrating() {
        assertThat(JobStatus.ANALYZING.isInProgress()).isTrue();
        assertThat(JobStatus.MIGRATING.isInProgress()).isTrue();
    }

    @Test
    void notInProgressStatuses_areNotInProgress() {
        assertThat(JobStatus.PENDING.isInProgress()).isFalse();
        assertThat(JobStatus.DONE.isInProgress()).isFalse();
        assertThat(JobStatus.FAILED.isInProgress()).isFalse();
        assertThat(JobStatus.CANCELLED.isInProgress()).isFalse();
    }
}
