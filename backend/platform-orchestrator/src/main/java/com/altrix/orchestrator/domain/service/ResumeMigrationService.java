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
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.ResumeMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
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
 * <p>The validator retry loop is intentionally NOT replicated here in this
 * first pass — a single attempt is taken.  Adding it later requires extracting
 * {@code RetryContextBuilder} into a domain port and is a separate ticket.
 */
@Slf4j
@RequiredArgsConstructor
public class ResumeMigrationService implements ResumeMigrationUseCase {

    private final WorkflowSessionRepository sessionRepository;
    private final MigrationAgent<ApprovedPlan, MigrationArtifact> migrator;
    private final MigrationAgent<MigrationArtifact, ValidationReport> validator;
    private final MigrationAgent<WorkflowOutcome, MigrationReport> reporter;
    private final MigratedFileStoragePort migratedFileStoragePort;
    private final JobStatusUpdatePort jobStatusUpdatePort;
    private final ProgressNotifierPort progressNotifierPort;

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
            // ── Migrator ────────────────────────────────────────────────────
            progressNotifierPort.notify(jobId, "Core Migrator", "RUNNING", null);
            MigrationArtifact artifact = migrator.execute(approvedPlan);
            progressNotifierPort.notify(jobId, "Core Migrator", "DONE", null);

            jobStatusUpdatePort.markMigrating(jobId);

            // ── Validator (single attempt — graph's retry loop is not here) ─
            progressNotifierPort.notify(jobId, "Sandbox Validator", "RUNNING", null);
            ValidationReport validation = validator.execute(artifact);
            progressNotifierPort.notify(jobId, "Sandbox Validator", "DONE", null);

            // ── Reporter ────────────────────────────────────────────────────
            // analysisReport is intentionally null — at resume time the graph's
            // working state is gone.  The reporter agent must tolerate a null
            // analysisReport (it already does today).
            WorkflowOutcome outcome = new WorkflowOutcome(
                    session.projectId(), null, plan, artifact, validation);

            progressNotifierPort.notify(jobId, "Report Generator", "RUNNING", null);
            reporter.execute(outcome);
            progressNotifierPort.notify(jobId, "Report Generator", "DONE", null);

            // ── Persist + mark complete ─────────────────────────────────────
            List<MigratedFile> files = artifact != null ? artifact.files() : List.of();
            String outputKey = migratedFileStoragePort.storeMigratedZip(jobId, files);

            session.resetAgentErrors();
            session.storeMigratedFiles(files);
            session.complete();
            sessionRepository.save(session);

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
