package com.migrator.orchestrator.adapter.in.rest.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inbound REST payload for creating or updating a provider config override.
 * All fields are optional — omit any field to leave it unchanged.
 * Pass an empty string to clear an override and revert to the system default.
 */
public record SaveProviderConfigRequest(
        Boolean enabled,

        @Size(max = 500, message = "API key must not exceed 500 characters")
        String apiKey,

        @Size(max = 500, message = "Base URL must not exceed 500 characters")
        @Pattern(regexp = "^(https?://.*)?$", message = "Base URL must start with http:// or https://")
        String baseUrl,

        @Size(max = 200, message = "Model name must not exceed 200 characters")
        String modelAnalysis,

        @Size(max = 200, message = "Model name must not exceed 200 characters")
        String modelMigration
) {}
