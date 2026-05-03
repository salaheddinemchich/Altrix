package com.altrix.orchestrator.adapter.out.encryption;

import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.infrastructure.config.EncryptionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for API keys stored in the database.
 *
 * <p>Format: {@code Base64(IV) + ":" + Base64(ciphertext+authTag)}
 * A fresh 12-byte random IV is generated per encryption call, so identical
 * plaintexts produce different ciphertexts — resistant to chosen-plaintext attacks.
 *
 * <p>Requires {@code PROVIDER_CONFIG_ENCRYPTION_KEY} to be a Base64-encoded
 * 32-byte (256-bit) secret. The adapter throws {@link IllegalStateException}
 * at the first encrypt call if the key is not configured, so misconfiguration
 * is caught immediately rather than silently storing plaintext.
 */
@Slf4j
@Component
public class AesGcmEncryptionAdapter implements ApiKeyEncryptionPort {

    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH  = 12;
    private static final int    GCM_TAG_BITS   = 128;

    private final SecretKey secretKey;

    public AesGcmEncryptionAdapter(EncryptionConfig config) {
        if (config.key().isBlank()) {
            log.warn("PROVIDER_CONFIG_ENCRYPTION_KEY is not set — " +
                     "saving custom API keys will be disabled until the key is configured.");
            this.secretKey = null;
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(config.key());
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                        "PROVIDER_CONFIG_ENCRYPTION_KEY must be a Base64-encoded 32-byte (256-bit) key. " +
                        "Got " + keyBytes.length + " bytes.");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("AesGcmEncryptionAdapter ready — API key encryption enabled");
        }
    }

    @Override
    public String encrypt(String plaintext) {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Cannot encrypt API key: PROVIDER_CONFIG_ENCRYPTION_KEY is not configured. " +
                    "Set the environment variable to a Base64-encoded 32-byte AES key.");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());
            return Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt API key", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Cannot decrypt API key: PROVIDER_CONFIG_ENCRYPTION_KEY is not configured.");
        }
        try {
            String[] parts = ciphertext.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid ciphertext format — expected IV:ciphertext");
            }
            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted  = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            return new String(cipher.doFinal(encrypted));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt API key", e);
        }
    }
}
