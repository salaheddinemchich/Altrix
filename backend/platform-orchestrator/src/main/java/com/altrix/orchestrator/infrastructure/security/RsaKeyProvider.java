package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.infrastructure.config.JwtConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Provides the RSA key pair used to sign and verify JWT tokens.
 *
 * <p>Production: load PKCS#8 private key + X.509 public key from Base64-encoded
 * environment variables {@code JWT_PRIVATE_KEY} / {@code JWT_PUBLIC_KEY}.
 *
 * <p>Dev/test fallback: generates a 2048-bit ephemeral key pair at startup.
 * Tokens are invalidated on every restart — intentionally unsuitable for production.
 *
 * <p>2048-bit RSA is the NIST-recommended minimum; 4096-bit recommended for long-lived keys.
 */
@Slf4j
@Component
public class RsaKeyProvider {

    private final KeyPair keyPair;

    public RsaKeyProvider(JwtConfig config) {
        if (!config.privateKey().isBlank() && !config.publicKey().isBlank()) {
            this.keyPair = loadFromConfig(config.privateKey(), config.publicKey());
            log.info("Loaded RSA key pair from configuration (RS256 JWT signing active)");
        } else {
            log.warn("No RSA keys configured — generating EPHEMERAL key pair. " +
                     "All tokens will be invalidated on restart. " +
                     "Set JWT_PRIVATE_KEY and JWT_PUBLIC_KEY for production.");
            this.keyPair = generateEphemeralKeyPair();
        }
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    private static KeyPair loadFromConfig(String privateKeyBase64, String publicKeyBase64) {
        try {
            byte[] privBytes = Base64.getDecoder().decode(privateKeyBase64.replaceAll("\\s", ""));
            byte[] pubBytes  = Base64.getDecoder().decode(publicKeyBase64.replaceAll("\\s", ""));

            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey  publicKey  = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load RSA key pair from configuration — check JWT_PRIVATE_KEY / JWT_PUBLIC_KEY", e);
        }
    }

    private static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }
}
