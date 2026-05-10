package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AES-256-GCM key used to encrypt API keys at rest.
 * The key must be a Base64-encoded 32-byte secret set via the
 * {@code PROVIDER_CONFIG_ENCRYPTION_KEY} environment variable.
 *
 * <p>If the key is blank, the encryption adapter will refuse to encrypt
 * (fail-fast at first write attempt) but will still allow reads of
 * unencrypted rows (there are none by default).
 */
@ConfigurationProperties(prefix = "encryption.provider-config")
public record EncryptionConfig(
        @DefaultValue("") String key
) {
}
