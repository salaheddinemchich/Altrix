package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import jakarta.validation.constraints.NotBlank;

public record IngestProjectRequest(
        @NotBlank String repoFullName,
        @NotBlank String defaultBranch,
        ConfigFormatPreference configFormatPreference,
        JakartaMessagingTarget jakartaMessagingTarget
) {}
