package com.altrix.orchestrator.domain.port.in;

/**
 * Command to create or update a provider config override.
 *
 * <p>All fields are optional (nullable). {@code null} means "leave unchanged".
 * Pass an empty string to explicitly clear an override and revert to the
 * system default.
 */
public record SaveProviderConfigCommand(
        String providerId,
        Boolean enabled,
        String plainApiKey,     // null = no change; "" = clear override
        String baseUrl,         // null = no change; "" = clear override
        String modelAnalysis,   // null = no change; "" = clear override
        String modelMigration   // null = no change; "" = clear override
) {
}
