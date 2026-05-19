package com.altrix.project.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    // Expected HMAC-SHA256(secret, "Hello, World!") taken from the GitHub docs example:
    // https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
    private static final String EXPECTED_HEX =
            "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WebhookSignatureVerifier(SECRET);
    }

    @Test
    void accepts_correct_signature() {
        boolean ok = verifier.verify(PAYLOAD, "sha256=" + EXPECTED_HEX);
        assertThat(ok).isTrue();
    }

    @Test
    void accepts_when_signature_computed_locally() throws Exception {
        // Sanity check that the test harness HMAC matches the verifier's HMAC,
        // independent of the hard-coded GitHub reference vector.
        String localHex = computeHmac(SECRET, PAYLOAD);
        assertThat(verifier.verify(PAYLOAD, "sha256=" + localHex)).isTrue();
    }

    @Test
    void rejects_wrong_signature() {
        String bad = "sha256=" + "0".repeat(64);
        assertThat(verifier.verify(PAYLOAD, bad)).isFalse();
    }

    @Test
    void rejects_when_signature_header_missing() {
        assertThat(verifier.verify(PAYLOAD, null)).isFalse();
    }

    @Test
    void rejects_when_signature_prefix_missing() {
        assertThat(verifier.verify(PAYLOAD, EXPECTED_HEX)).isFalse();
    }

    @Test
    void rejects_when_signature_hex_is_malformed() {
        assertThat(verifier.verify(PAYLOAD, "sha256=not-hex")).isFalse();
        assertThat(verifier.verify(PAYLOAD, "sha256=abc")).isFalse(); // odd length
    }

    @Test
    void rejects_when_secret_is_not_configured() {
        WebhookSignatureVerifier blank = new WebhookSignatureVerifier("");
        assertThat(blank.verify(PAYLOAD, "sha256=" + EXPECTED_HEX)).isFalse();
    }

    @Test
    void rejects_when_payload_is_modified_by_one_byte() {
        byte[] tampered = PAYLOAD.clone();
        tampered[0] ^= 0x01;
        assertThat(verifier.verify(tampered, "sha256=" + EXPECTED_HEX)).isFalse();
    }

    private static String computeHmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
