package com.altrix.job.domain.model;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationJobTest {

    @Test
    void create_producesJobInPendingStatus() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "uploads/key.zip", null, null);

        assertThat(job.getId()).isNotBlank();
        assertThat(job.getProjectId()).isEqualTo("proj-1");
        assertThat(job.getUserId()).isEqualTo("user-1");
        assertThat(job.getProjectStorageKey()).isEqualTo("uploads/key.zip");
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getConfigFormatPreference()).isEqualTo(ConfigFormatPreference.KEEP_ORIGINAL);
        assertThat(job.getCreatedAt()).isNotNull();
    }

    @Test
    void happyPath_pendingToAnalyzingToMigratingToDone() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "key", null, null);

        MigrationJob analyzing = job.startAnalyzing();
        assertThat(analyzing.getStatus()).isEqualTo(JobStatus.ANALYZING);

        MigrationJob migrating = analyzing.startMigrating();
        assertThat(migrating.getStatus()).isEqualTo(JobStatus.MIGRATING);

        MigrationJob done = migrating.complete("migrated/job-1/output.zip");
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getOutputStorageKey()).isEqualTo("migrated/job-1/output.zip");
        assertThat(done.getCompletedAt()).isNotNull();
    }

    @Test
    void fail_setsErrorMessageAndTerminalStatus() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "key", null, null)
                .startAnalyzing();

        MigrationJob failed = job.fail("AI timeout");

        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("AI timeout");
        assertThat(failed.isTerminalStatus()).isTrue();
    }

    @Test
    void cancel_setsTerminalStatus() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "key", null, null);
        MigrationJob cancelled = job.cancel();

        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.isTerminalStatus()).isTrue();
    }

    @Test
    void transitionFromTerminal_throwsIllegalStateException() {
        MigrationJob done = MigrationJob.create("proj-1", "user-1", "key", null, null)
                .startAnalyzing()
                .startMigrating()
                .complete("output.zip");

        assertThatThrownBy(done::startAnalyzing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void immutability_originalUnchangedAfterTransition() {
        MigrationJob original = MigrationJob.create("proj-1", "user-1", "key", null, null);
        original.startAnalyzing();

        assertThat(original.getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void equality_basedOnIdOnly() {
        MigrationJob a = MigrationJob.create("proj-1", "user-1", "key", null, null);
        MigrationJob b = a.startAnalyzing();

        assertThat(a).isEqualTo(b); // same id
        assertThat(a).hasSameHashCodeAs(b);
    }
}
