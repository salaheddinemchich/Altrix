package com.altrix.project.domain.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object describing a freshly cloned Git repository on disk.
 *
 * <p>Issue #74: returned by {@code RepositoryIngestionPort.clone(...)} after a
 * successful {@code git clone}. The {@link #workspacePath} points at the local
 * working tree (HEAD already checked out at {@link #branch}); callers are
 * responsible for invoking {@code RepositoryIngestionPort.cleanup(path)} once
 * downstream processing has consumed it.
 *
 * @param workspacePath absolute path to the cloned working tree on local disk
 * @param remoteUrl     the upstream URL the snapshot was cloned from
 * @param branch        the branch or tag ref that is checked out
 * @param commitSha     the 40-character SHA-1 of HEAD at clone time
 * @param sizeBytes     total size of the working tree on disk, in bytes
 * @param shallow       {@code true} if the clone was created with {@code --depth}
 * @param clonedAt      timestamp when the snapshot was produced
 */
public record RepositorySnapshot(
        Path workspacePath,
        String remoteUrl,
        String branch,
        String commitSha,
        long sizeBytes,
        boolean shallow,
        Instant clonedAt
) {
    public RepositorySnapshot {
        Objects.requireNonNull(workspacePath, "workspacePath");
        Objects.requireNonNull(remoteUrl, "remoteUrl");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(commitSha, "commitSha");
        Objects.requireNonNull(clonedAt, "clonedAt");
        if (commitSha.length() != 40) {
            throw new IllegalArgumentException("commitSha must be a 40-char SHA-1, got: " + commitSha);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative, got: " + sizeBytes);
        }
    }
}
