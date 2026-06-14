package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused on #98 — the migrator → validator retry loop inside
 * {@link ResumeMigrationService#resume}.  Persistence + completion paths
 * are stubbed; the assertions are about <em>how many times the agents
 * were called</em> and <em>what retry context was fed back</em>.
 */
@ExtendWith(MockitoExtension.class)
// Lenient: setUp() seeds defaults used by most-but-not-all tests
// (the pure unit test for buildRetryContext doesn't touch the mocks).
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeMigrationServiceTest {

    @Mock WorkflowSessionRepository sessionRepository;
    @Mock MigrationAgent<ApprovedPlan, MigrationArtifact> migrator;
    @Mock MigrationAgent<MigrationArtifact, MigrationArtifact> semanticValidator;
    @Mock MigrationAgent<MigrationArtifact, ValidationReport> validator;
    @Mock MigrationAgent<WorkflowOutcome, MigrationReport> reporter;
    @Mock MigratedFileStoragePort migratedFileStoragePort;
    @Mock JobStatusUpdatePort jobStatusUpdatePort;
    @Mock ProgressNotifierPort progressNotifier;
    @Mock MigrationReportRepository migrationReportRepository;

    private static final WorkflowSessionId SESSION_ID = WorkflowSessionId.generate();
    private static final String JOB_ID = "job-1";
    private static final String PROJECT_ID = "proj-1";

    private MigrationArtifact artifact;

    @BeforeEach
    void setUp() {
        artifact = MigrationArtifact.empty(PROJECT_ID);

        // Session in MIGRATING state with a plan — minimum the service needs.
        MigrationPlan plan = new MigrationPlan(
                PROJECT_ID, "storage-key", "Spring Boot 3 + Kafka",
                List.of(), "MEDIUM", "1d", "summary", List.of("a.java"));
        WorkflowSession session = new WorkflowSession(
                SESSION_ID, JOB_ID, PROJECT_ID, SessionStatus.MIGRATING,
                plan, null, null, 0, List.of(), Instant.now(), 0L);

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(migratedFileStoragePort.storeMigratedZip(any(), any())).thenReturn("output-key");
        when(reporter.execute(any())).thenReturn(mock(MigrationReport.class));
        // Semantic validator is a pass-through in Stage 3 — return the artifact it receives.
        when(semanticValidator.execute(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void validationPassesFirstTry_callsMigratorOnce_noRetryContext() {
        when(migrator.execute(any())).thenReturn(artifact);
        when(validator.execute(any())).thenReturn(pass());

        service(/*maxRetries*/ 2).resume(SESSION_ID);

        verify(migrator, times(1)).execute(any());
        verify(validator, times(1)).execute(any());

        ArgumentCaptor<ApprovedPlan> captor = ArgumentCaptor.forClass(ApprovedPlan.class);
        verify(migrator).execute(captor.capture());
        assertThat(captor.getValue().retryContext()).isNull();
    }

    @Test
    void validationFailsThenPasses_callsMigratorTwice_feedsRetryContext() {
        when(migrator.execute(any())).thenReturn(artifact);
        when(validator.execute(any()))
                .thenReturn(fail("a.java: Pub/Sub import not removed"))
                .thenReturn(pass());

        service(/*maxRetries*/ 2).resume(SESSION_ID);

        verify(migrator, times(2)).execute(any());
        verify(validator, times(2)).execute(any());

        ArgumentCaptor<ApprovedPlan> captor = ArgumentCaptor.forClass(ApprovedPlan.class);
        verify(migrator, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(0).retryContext()).isNull();
        assertThat(captor.getAllValues().get(1).retryContext())
                .isNotNull()
                .contains("Pub/Sub import not removed")
                .contains("Previous migration attempt #1");
    }

    @Test
    void validationAlwaysFails_stopsAfterMaxRetriesPlusOne_finalReportUsesLastAttempt() {
        when(migrator.execute(any())).thenReturn(artifact);
        when(validator.execute(any())).thenReturn(fail("still broken"));

        service(/*maxRetries*/ 2).resume(SESSION_ID);

        // 1 initial + 2 retries = 3 attempts total.
        verify(migrator, times(3)).execute(any());
        verify(validator, times(3)).execute(any());
        // Reporter still runs (we always carry the last attempt forward).
        verify(reporter, times(1)).execute(any());
    }

    @Test
    void maxRetriesZero_restoresSingleAttemptBehaviour() {
        when(migrator.execute(any())).thenReturn(artifact);
        when(validator.execute(any())).thenReturn(fail("still broken"));

        service(/*maxRetries*/ 0).resume(SESSION_ID);

        verify(migrator, times(1)).execute(any());
        verify(validator, times(1)).execute(any());
    }

    @Test
    void maxRetriesNegative_clampedToZero() {
        when(migrator.execute(any())).thenReturn(artifact);
        when(validator.execute(any())).thenReturn(fail("still broken"));

        service(/*maxRetries*/ -5).resume(SESSION_ID);

        verify(migrator, times(1)).execute(any());
    }

    @Test
    void maxRetriesAbsurdlyHigh_clampedToFive() {
        when(migrator.execute(any())).thenReturn(artifact);
        when(validator.execute(any())).thenReturn(fail("still broken"));

        service(/*maxRetries*/ 100).resume(SESSION_ID);

        verify(migrator, times(6)).execute(any()); // 1 initial + 5 retries (clamp ceiling)
    }

    @Test
    void retryContextBuilder_truncatesAfter20Failures() {
        List<String> manyFailures = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) manyFailures.add("file" + i + ".java: residual import");

        String ctx = ResumeMigrationService.buildRetryContext(
                1, new ValidationReport(PROJECT_ID, false, manyFailures, "many"));

        assertThat(ctx).contains("file0.java");
        assertThat(ctx).contains("file19.java");
        assertThat(ctx).doesNotContain("file20.java");
        assertThat(ctx).contains("5 more (truncated)");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResumeMigrationService service(int maxRetries) {
        return new ResumeMigrationService(
                sessionRepository, migrator, semanticValidator, validator, reporter,
                migratedFileStoragePort, jobStatusUpdatePort, progressNotifier,
                migrationReportRepository, maxRetries);
    }

    private static ValidationReport pass() {
        return new ValidationReport(PROJECT_ID, true, List.of(), "ok");
    }

    private static ValidationReport fail(String... failures) {
        return new ValidationReport(PROJECT_ID, false, List.of(failures),
                failures.length + " issue(s)");
    }
}
