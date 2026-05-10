package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing and expiry settings.
 *
 * <p>RS256 (RSA-SHA256) is mandatory — asymmetric signing lets any downstream service
 * verify tokens with the public key alone; no service ever needs the private key.
 * HS256 shared-secret is rejected: a compromised resource-server could forge tokens.
 *
 * <p>Keys are Base64-encoded DER bytes (PKCS#8 private, X.509 public).
 * Generate once and inject via environment variables:
 * <pre>
 *   openssl genrsa -out private.pem 2048
 *   openssl pkcs8 -topk8 -nocrypt -in private.pem -outform DER | base64 -w0   # JWT_PRIVATE_KEY
 *   openssl rsa  -in private.pem -pubout  -outform DER | base64 -w0            # JWT_PUBLIC_KEY
 * </pre>
 *
 * <p>When both keys are absent (local dev only) an ephemeral pair is generated at
 * startup with a WARN log. All tokens from that run are invalid after restart.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtConfig(
        String privateKey,
        String publicKey,
        int accessTokenExpiryMinutes,
        int refreshTokenExpiryDays
) {
    public JwtConfig {
        if (privateKey == null) privateKey = "";
        if (publicKey == null) publicKey = "";
        if (accessTokenExpiryMinutes <= 0) accessTokenExpiryMinutes = 15;
        if (refreshTokenExpiryDays <= 0) refreshTokenExpiryDays = 7;
    }
}
