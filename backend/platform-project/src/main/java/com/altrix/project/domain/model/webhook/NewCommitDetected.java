package com.altrix.project.domain.model.webhook;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event raised by the webhook receiver when an accepted GitHub
 * {@code push} event lands for a tracked repository / branch (#90).
 *
 * <p>Downstream consumers (initially the orchestrator's WorkflowSession
 * starter, in a follow-up commit) look up the user who owns the repository
 * and trigger a fresh JGit clone using the existing {@code /clone} flow.
 *
 * @param repoUrl    HTTPS clone URL, e.g. {@code https://github.com/acme/widgets.git}
 * @param branch     branch name without the {@code refs/heads/} prefix,
 *                   e.g. {@code "main"}
 * @param commitSha  40-char SHA-1 of the head commit pushed
 * @param deliveryId GitHub delivery id (X-GitHub-Delivery) — links the event
 *                   back to the audit-log row that produced it
 * @param detectedAt timestamp when the webhook receiver validated the event
 */
public record NewCommitDetected(
        String repoUrl,
        String branch,
        String commitSha,
        String deliveryId,
        Instant detectedAt
) {
    public NewCommitDetected {
        Objects.requireNonNull(repoUrl,    "repoUrl");
        Objects.requireNonNull(branch,     "branch");
        Objects.requireNonNull(commitSha,  "commitSha");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
