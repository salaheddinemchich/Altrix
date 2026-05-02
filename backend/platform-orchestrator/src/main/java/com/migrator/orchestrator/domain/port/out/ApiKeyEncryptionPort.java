package com.migrator.orchestrator.domain.port.out;

/**
 * Secondary port for symmetric encryption of API keys at rest.
 * The domain never holds plaintext keys after they pass through this boundary.
 */
public interface ApiKeyEncryptionPort {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
