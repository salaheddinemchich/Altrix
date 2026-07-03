package com.altrix.project.domain.port.in;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.project.domain.model.Project;

/**
 * Driving port — register a new project by cloning a remote Git repository.
 *
 * <p>Issue #12: replaces the ZIP-upload flow with a true Git clone so projects
 * keep their commit history, support any Git host, and can be re-migrated
 * automatically via webhooks (#15).
 */
public interface IngestGitRepositoryUseCase {

    /**
     * Clone {@code repoUrl} at {@code branch}, package the working tree as a ZIP,
     * stage it in MinIO, run framework / build-system detection, persist a
     * {@link Project} row, and publish the registration event.
     *
     * @throws com.altrix.common.exception.RepositoryIngestionException on
     *                                                                  invalid URL, authentication failure, branch not found, or
     *                                                                  repository exceeding the configured size cap
     */
    Project ingestFromGit(GitIngestionCommand command);

    /**
     * Input command for {@link #ingestFromGit(GitIngestionCommand)}.
     *
     * @param userId                 owning user — required
     * @param repoUrl                HTTPS Git URL — required
     * @param branch                 branch or tag ref to check out, {@code null} for HEAD
     * @param accessToken            HTTPS token for private repos, {@code null} for public
     * @param shallow                {@code true} to request a shallow clone (depth = 50)
     * @param configFormatPreference user-supplied config-format preference, optional
     * @param jakartaMessagingTarget user's explicit choice of Jakarta EE messaging
     *                               output, optional; only meaningful once detection
     *                               confirms Jakarta EE, ignored otherwise
     */
    record GitIngestionCommand(
            String userId,
            String repoUrl,
            String branch,
            String accessToken,
            boolean shallow,
            ConfigFormatPreference configFormatPreference,
            JakartaMessagingTarget jakartaMessagingTarget
    ) {
        public GitIngestionCommand {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId must not be blank");
            }
            if (repoUrl == null || repoUrl.isBlank()) {
                throw new IllegalArgumentException("repoUrl must not be blank");
            }
        }
    }
}
