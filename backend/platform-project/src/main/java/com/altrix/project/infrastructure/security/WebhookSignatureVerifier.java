package com.altrix.project.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies the {@code X-Hub-Signature-256} header GitHub sends with every
 * webhook delivery (#89).
 *
 * <p>Algorithm — HMAC-SHA256 over the raw request body, using the shared
 * webhook secret as the key.  GitHub encodes the result as the literal string
 * {@code sha256=<hex>}, so we match the prefix and constant-time-compare the
 * hex tail against our own computation.
 *
 * <p>Constant-time comparison is critical here: a naïve {@code String.equals}
 * would short-circuit on the first byte mismatch and leak timing information,
 * letting an attacker incrementally discover the expected signature byte-by-byte.
 */
@Slf4j
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final String secret;

    public WebhookSignatureVerifier(@Value("${webhook.github.secret:}") String secret) {
        this.secret = secret;
    }

    /**
     * @param body            raw request body as received off the wire — must not
     *                        be modified or re-serialised before this call, since
     *                        any whitespace difference breaks the HMAC
     * @param signatureHeader value of {@code X-Hub-Signature-256}, e.g.
     *                        {@code "sha256=ab12..."}, or {@code null}
     * @return {@code true} when the signature is present, well-formed, and
     *         matches; {@code false} in every other case (including when the
     *         secret is not configured — fail closed)
     */
    public boolean verify(byte[] body, String signatureHeader) {
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook signature verification SKIPPED: webhook.github.secret not configured");
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length());

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] computed = mac.doFinal(body);
            byte[] expected = hexDecode(providedHex);
            if (expected == null) return false;
            return MessageDigest.isEqual(computed, expected);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC-SHA256 unavailable on this JVM — this should never happen", e);
            return false;
        }
    }

    /** Decode a lowercase hex string, returning {@code null} on malformed input. */
    private static byte[] hexDecode(String hex) {
        if (hex.length() % 2 != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
