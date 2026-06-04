package com.altrix.orchestrator.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationApplyConfigTest {

    private MigrationApplyConfig config() {
        return new MigrationApplyConfig(
                "altrix/migration-",
                "msg {sessionId}",
                "title {sessionId}",
                "body {sessionId}",
                "^[A-Za-z0-9._/-]{1,128}$",
                Duration.ofMinutes(15));
    }

    @Test
    void branchNameValidation_acceptsTypicalNames() {
        var c = config();
        assertThat(c.isValidBranchName("altrix/migration-123e4567")).isTrue();
        assertThat(c.isValidBranchName("feature/x")).isTrue();
        assertThat(c.isValidBranchName("hotfix-3.2.1")).isTrue();
    }

    @Test
    void branchNameValidation_rejectsBadNames() {
        var c = config();
        assertThat(c.isValidBranchName(null)).isFalse();
        assertThat(c.isValidBranchName("")).isFalse();
        assertThat(c.isValidBranchName("with spaces")).isFalse();
        assertThat(c.isValidBranchName("x".repeat(200))).isFalse(); // length cap
        assertThat(c.isValidBranchName("backtick`")).isFalse();
        assertThat(c.isValidBranchName("has~tilde")).isFalse();
    }

    @Test
    void renderTemplate_substitutesSessionId() {
        var c = config();
        assertThat(c.renderTemplate("commit for {sessionId}", "abc")).isEqualTo("commit for abc");
        assertThat(c.renderTemplate("no placeholder here", "abc")).isEqualTo("no placeholder here");
        assertThat(c.renderTemplate(null, "abc")).isEmpty();
    }

    @Test
    void compactConstructor_fillsAllNullDefaults() {
        var c = new MigrationApplyConfig(null, null, null, null, null, null);
        assertThat(c.branchNamePrefix()).isEqualTo("altrix/migration-");
        assertThat(c.confirmationTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(c.compiledBranchNamePattern()).isNotNull();
    }
}
