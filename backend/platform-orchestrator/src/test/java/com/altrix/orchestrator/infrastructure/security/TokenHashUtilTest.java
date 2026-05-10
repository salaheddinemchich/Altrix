package com.altrix.orchestrator.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHashUtilTest {

    @Test
    void same_input_produces_same_hash() {
        String hash1 = TokenHashUtil.sha256Hex("my-refresh-token");
        String hash2 = TokenHashUtil.sha256Hex("my-refresh-token");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void different_inputs_produce_different_hashes() {
        String h1 = TokenHashUtil.sha256Hex("token-a");
        String h2 = TokenHashUtil.sha256Hex("token-b");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void output_is_64_hex_characters() {
        String hash = TokenHashUtil.sha256Hex("any-token");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void empty_string_produces_known_sha256() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertThat(TokenHashUtil.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
