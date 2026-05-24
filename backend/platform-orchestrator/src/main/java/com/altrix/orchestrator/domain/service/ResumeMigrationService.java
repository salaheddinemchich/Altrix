package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import com.altrix.orchestrator.domain.model.sandbox.SandboxContext;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.ResumeMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Domain service that continues the pipeline after the human approval gate
 * (#10).  Mirrors the migrator → validator → reporter portion of
 * {@link com.altrix.orchestrator.infrastructure.workflow.MigrationWorkflowGraph}
 * but runs sequentially because:
 * <ul>
 *   <li>By the time approval lands, the graph thread has long since exited
 *       and its checkpoint is at the END node — there is nothing to resume.</li>
 *   <li>The continuation is invoked from a Spring {@code TaskExecutor}, so
 *       blocking is fine; the originating REST call already returned.</li>
 * </ul>
 *
 * <p>#98 — when the validator returns failures, the migrator is re-invoked
 * with a structured retry context describing those failures, up to
 * {@code maxRetries} extra attempts.  The retry loop uses ONLY the last
 * attempt's artifact + validation for the final report / persistence —
 * intermediate artefacts are discarded so the user never sees a half-good
 * intermediate state.
 */
@Slf4j
public class ResumeMigrationService implements ResumeMigrationUseCase {

    private final WorkflowSessionRepository sessionRepository;
    private final MigrationAgent<ApprovedPlan, MigrationArtifact> migrator;
    private final MigrationAgent<MigrationArtifact, ValidationReport> validator;
    private final MigrationAgent<WorkflowOutcome, MigrationReport> reporter;
    private final MigratedFileStoragePort migratedFileStoragePort;
    private final JobStatusUpdatePort jobStatusUpdatePort;
    private final ProgressNotifierPort progressNotifierPort;
    /** #129 — persists the markdown report Agent 5 produces. */
    private final MigrationReportRepository migrationReportRepository;
    /**
     * #98 — extra migrator invocations beyond the initial run.  Default 1
     * (so up to 2 total passes).  0 disables retry entirely, restoring
     * the pre-#98 single-attempt behaviour.  Tuneable via
     * {@code migration.validation.max-retries} (see BeanConfig).
     */
    private final int maxRetries;

    public ResumeMigrationService(WorkflowSessionRepository sessionRepository,
                                  MigrationAgent<ApprovedPlan, MigrationArtifact> migrator,
                                  MigrationAgent<MigrationArtifact, ValidationReport> validator,
                                  MigrationAgent<WorkflowOutcome, MigrationReport> reporter,
                                  MigratedFileStoragePort migratedFileStoragePort,
                                  JobStatusUpdatePort jobStatusUpdatePort,
                                  ProgressNotifierPort progressNotifierPort,
                                  MigrationReportRepository migrationReportRepository,
                                  int maxRetries) {
        this.sessionRepository = sessionRepository;
        this.migrator = migrator;
        this.validator = validator;
        this.reporter = reporter;
        this.migratedFileStoragePort = migratedFileStoragePort;
        this.jobStatusUpdatePort = jobStatusUpdatePort;
        this.progressNotifierPort = progressNotifierPort;
        this.migrationReportRepository = migrationReportRepository;
        // Clamp to a sensible band so a misconfig can't trigger 100 LLM calls.
        this.maxRetries = Math.max(0, Math.min(maxRetries, 5));
    }

    @Override
    public void resume(WorkflowSessionId sessionId) {
        WorkflowSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        String jobId = session.jobId();

        MigrationPlan plan = session.plan();
        if (plan == null) {
            String msg = "Cannot resume — session " + sessionId + " has no plan";
            log.error(msg);
            failSessionBestEffort(session, msg);
            jobStatusUpdatePort.markFailed(jobId, msg);
            return;
        }

        // The plan stored on the session may have been edited by the reviewer
        // before approving — that edited version is what we use.  approvedBy
        // is "reviewer" because we don't carry the actor through approval yet;
        // when audit is wired (#125) this becomes the JWT sub.
        ApprovedPlan approvedPlan = new ApprovedPlan(plan, "reviewer", Instant.now(), null);

        try {
            // ── Migrate → validate, retrying on validation failures (#98) ───
            // The loop always returns the LAST attempt's pair.  Intermediate
            // failed artefacts are discarded — the user only sees the final
            // result (either the first PASS or the last attempt's output).
            // sessionId is threaded through SandboxContext so Docker runners
            // can persist their captured logs (#105).
            MigrateValidateResult mv = migrateWithRetries(jobId, approvedPlan, sessionId.value().toString());
            MigrationArtifact artifact = mv.artifact();
            ValidationReport validation = mv.validation();

            // ── Reporter ────────────────────────────────────────────────────
            // analysisReport is intentionally null — at resume time the graph's
            // working state is gone.  The reporter agent must tolerate a null
            // analysisReport (it already does today).
            WorkflowOutcome outcome = new WorkflowOutcome(
                    session.projectId(), null, plan, artifact, validation);

            progressNotifierPort.notify(jobId, "Report Generator", "RUNNING", null);
            MigrationReport report = reporter.execute(outcome);
            progressNotifierPort.notify(jobId, "Report Generator", "DONE", null);

            // #129 — persist the narrative report so it can be retrieved long
            // after the pipeline ends.  Best-effort: a DB hiccup here doesn't
            // un-do the migration (the ZIP is already in MinIO).
            try {
                if (report != null) migrationReportRepository.save(sessionId, report);
            } catch (Exception persistErr) {
                log.warn("Could not persist migration report for session '{}' (non-fatal): {}",
                        sessionId, persistErr.getMessage());
            }

            // ── Persist artifact first ──────────────────────────────────────
            // Store the migrated ZIP in MinIO BEFORE touching the session row.
            // If a concurrent pause/resume incremented the row version while we
            // were running the AI, the session save below may collide — but the
            // artifact is already durable so the user doesn't lose any work.
            List<MigratedFile> files = artifact != null ? artifact.files() : List.of();
            String outputKey = migratedFileStoragePort.storeMigratedZip(jobId, files);

            persistCompletionBestEffort(sessionId, files, outputKey);

            jobStatusUpdatePort.markDone(jobId, outputKey);
            progressNotifierPort.notify(jobId, "Pipeline", "DONE",
                    "Migration complete. Ready to download.");
            log.info("Resume pipeline DONE for job '{}' session '{}'", jobId, sessionId);

        } catch (Exception e) {
            log.error("Resume pipeline FAILED for job '{}' session '{}': {}",
                    jobId, sessionId, e.getMessage(), e);
            failSessionBestEffort(session, e.getMessage());
            jobStatusUpdatePort.markFailed(jobId, e.getMessage());
            progressNotifierPort.notify(jobId, "Pipeline", "FAILED", e.getMessage());
        }
    }

    /** Bundles the artifact + validation pair the retry loop produces. */
    private record MigrateValidateResult(MigrationArtifact artifact, ValidationReport validation) {}

    /**
     * Runs Agent 3 → Agent 4 in a loop, feeding validation failures from
     * attempt N back into attempt N+1's retry context.  Stops at:
     *   • validation.passed = true, or
     *   • attempt count > 1 + maxRetries.
     *
     * <p>Each attempt emits its own RUNNING / DONE pair on the progress
     * topic with the attempt number in the message so the JobDetail
     * timeline can show "Migrating (retry 1/2)…" instead of going silent
     * during a long re-run.
     */
    private MigrateValidateResult migrateWithRetries(String jobId, ApprovedPlan basePlan) {
        return migrateWithRetries(jobId, basePlan, null);
    }

    /**
     * Same as the no-arg version but threads a sessionId through
     * {@link SandboxContext} so Docker runners can persist their captured
     * logs (#105).  The two-arg form is used by {@link #resume}; the no-arg
     * overload exists for tests that don't care about log persistence.
     */
    private MigrateValidateResult migrateWithRetries(String jobId, ApprovedPlan basePlan, String sessionIdForContext) {
        // markMigrating once — subsequent retries stay in the same job status.
        jobStatusUpdatePort.markMigrating(jobId);

        ApprovedPlan plan = basePlan;
        MigrationArtifact artifact = null;
        ValidationReport validation = null;

        int totalAttempts = 1 + maxRetries;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            String attemptLabel = attempt == 1
                    ? null
                    : "retry %d/%d".formatted(attempt - 1, maxRetries);

            // ── Migrator ────────────────────────────────────────────────
            progressNotifierPort.notify(jobId, "Core Migrator", "RUNNING", attemptLabel);
            artifact = migrator.execute(plan);
            progressNotifierPort.notify(jobId, "Core Migrator", "DONE", attemptLabel);

            // ── Validator ───────────────────────────────────────────────
            progressNotifierPort.notify(jobId, "Sandbox Validator", "RUNNING", attemptLabel);
            // #105 — make sessionId visible to Docker runners so they can
            // persist captured logs.  Cleared in finally below so a stray
            // value doesn't leak into another caller on the same thread.
            if (sessionIdForContext != null) SandboxContext.setSessionId(sessionIdForContext);
            try {
                validation = validator.execute(artifact);
            } finally {
                if (sessionIdForContext != null) SandboxContext.clear();
            }
            progressNotifierPort.notify(jobId, "Sandbox Validator", "DONE",
                    validation.passed() ? attemptLabel : (attemptLabel != null
                            ? attemptLabel + " — " + validation.failures().size() + " issue(s)"
                            : validation.failures().size() + " issue(s)"));

            if (validation.passed() || attempt == totalAttempts) {
                if (attempt > 1) {
                    log.info("Validation {} on attempt {}/{} for job '{}'",
                            validation.passed() ? "PASSED" : "FAILED",
                            attempt, totalAttempts, jobId);
                }
                return new MigrateValidateResult(artifact, validation);
            }

            // Build retry context for the next attempt and loop.
            String retryContext = buildRetryContext(attempt, validation);
            plan = new ApprovedPlan(plan.plan(), plan.approvedBy(), plan.approvedAt(), retryContext);
            log.info("Validation FAILED on attempt {}/{} for job '{}' — retrying with {} issue(s) in context",
                    attempt, totalAttempts, jobId, validation.failures().size());
        }

        // Loop body always returns; this is just to keep the compiler honest.
        return new MigrateValidateResult(artifact, validation);
    }

    /**
     * Renders validation failures as a short instruction the migrator can
     * prepend to its SYSTEM_PROMPT.  Kept terse — the model is already
     * carrying the full source content, so adding 200+ char paths × 50
     * failures would blow the context budget.  First 20 issues only.
     */
    static String buildRetryContext(int previousAttempt, ValidationReport failedValidation) {
        StringBuilder sb = new StringBuilder();
        sb.append("Previous migration attempt #").append(previousAttempt)
          .append(" produced the following validation failures. ")
          .append("Fix EACH of these in this attempt — re-rewrite the affected files so the listed issues are gone:\n");
        List<String> failures = failedValidation.failures();
        int limit = Math.min(failures.size(), 20);
        for (int i = 0; i < limit; i++) {
            sb.append("  - ").append(failures.get(i)).append('\n');
        }
        if (failures.size() > limit) {
            sb.append("  - … and ").append(failures.size() - limit).append(" more (truncated)\n");
        }
        return sb.toString();
    }

    /**
     * Persists migrated files + walks the session to DONE on a FRESH copy
     * re-fetched from the repository.  Re-fetching dodges the optimistic-lock
     * collision that happens when the user paused/resumed during the run:
     * those REST calls each incremented {@code @Version}, so the in-memory
     * session we've been holding for minutes is stale.
     *
     * <p>Failures here are logged but never re-thrown: the migrated artifact is
     * already in MinIO, and the job-side status will be marked DONE by the
     * caller via {@link JobStatusUpdatePort#markDone}.  Failing the entire
     * pipeline because of a row-version mismatch would lose the user's work.
     */
    private void persistCompletionBestEffort(WorkflowSessionId sessionId,
                                              List<MigratedFile> files,
                                              String outputKey) {
        try {
            WorkflowSession fresh = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new SessionNotFoundException(sessionId));
            fresh.resetAgentErrors();
            fresh.storeMigratedFiles(files);
            walkToCompletion(fresh, files.size());
            sessionRepository.save(fresh);
        } catch (Exception e) {
            log.warn("Could not persist session completion for '{}' (non-fatal — " +
                     "artifact stored at '{}', job will be marked DONE): {}",
                    sessionId, outputKey, e.getMessage());
        }
    }

    /**
     * Walks the session through whatever intermediate states remain until DONE.
     * Tolerates being called from MIGRATING / VALIDATING / PAUSED (the user may
     * have paused mid-run) and is a no-op on a session already DONE/FAILED.
     */
    private void walkToCompletion(WorkflowSession s, int fileCount) {
        if (s.status() == SessionStatus.PAUSED) {
            s.resume();
        }
        if (s.status() == SessionStatus.MIGRATING) {
            s.startValidation(fileCount);
        }
        if (s.status() == SessionStatus.VALIDATING) {
            s.complete();
        }
        // Anything else (DONE / FAILED / unexpected) — leave alone.
    }

    private void failSessionBestEffort(WorkflowSession session, String reason) {
        try {
            session.fail(reason);
            sessionRepository.save(session);
        } catch (Exception ex) {
            log.warn("Could not persist FAILED state for session '{}' (non-fatal): {}",
                    session.id(), ex.getMessage());
        }
    }
}
