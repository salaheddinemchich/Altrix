package com.altrix.orchestrator.domain.model.provider;

import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;

/**
 * Aggregate representing a user-supplied override for one AI provider.
 *
 * <p>All fields are nullable: {@code null} means "no override — use the system
 * default from application.yml". This lets users override only what they care
 * about (e.g. just the API key) without touching model names.
 *
 * <p>The {@code encryptedApiKey} is always stored encrypted. The domain layer
 * never holds the plaintext key — encryption/decryption is delegated to
 * {@link com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort}.
 */
@Getter
@Builder(toBuilder = true)
@With
public final class ProviderConfig {

    private final String  providerId;        // natural PK — e.g. "groq", "openai"
    private final Boolean enabled;           // null = use system default
    private final String  encryptedApiKey;   // null = use system default; never plaintext
    private final String  baseUrl;           // null = use system default
    private final String  modelAnalysis;     // null = use system default
    private final String  modelMigration;    // null = use system default
    private final Instant updatedAt;

    public static ProviderConfig createEmpty(String providerId) {
        return ProviderConfig.builder()
                .providerId(providerId)
                .updatedAt(Instant.now())
                .build();
    }

    public boolean hasCustomApiKey() {
        return encryptedApiKey != null && !encryptedApiKey.isBlank();
    }
}
