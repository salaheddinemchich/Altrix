package com.altrix.project.adapter.in.rest;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * HTTP request DTO for the {@code POST /api/v1/projects/clone} endpoint (#12).
 *
 * <p>{@code accessToken} is the HTTPS Personal Access Token used for private
 * repositories.  Never echoed back in responses and never logged.
 *
 * @param repoUrl                HTTPS Git URL, e.g. {@code https://github.com/user/repo.git}
 * @param branch                 branch or tag ref, or {@code null} for HEAD
 * @param accessToken            HTTPS PAT for private repos, or {@code null} for public
 * @param shallow                {@code true} to request a shallow clone (depth = 50)
 * @param configFormatPreference user's preferred output config format
 * @param jakartaMessagingTarget user's explicit choice of Jakarta EE messaging
 *                                output, optional; only meaningful once detection
 *                                confirms Jakarta EE, ignored otherwise
 */
public record CloneProjectRequest(

        @NotBlank
        @Pattern(
                regexp = "^https://.+",
                message = "repoUrl must be an HTTPS URL (SSH is not supported)")
        String repoUrl,

        String branch,
        String accessToken,
        boolean shallow,
        ConfigFormatPreference configFormatPreference,
        JakartaMessagingTarget jakartaMessagingTarget
) {
}
