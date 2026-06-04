package com.altrix.orchestrator.adapter.out.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformProjectMetadataAdapterTest {

    // ── provider-id derivation (the real production bug) ─────────────────────

    @Test
    void deriveProviderId_returnsGitHubForGitHubHttpsUrl() {
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId(
                "https://github.com/owner/repo.git")).isEqualTo("github");
    }

    @Test
    void deriveProviderId_returnsGitHubForGitHubSshUrl() {
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId(
                "git@github.com:owner/repo.git")).isEqualTo("github");
    }

    @Test
    void deriveProviderId_returnsGitLabForGitLab() {
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId(
                "https://gitlab.com/owner/repo.git")).isEqualTo("gitlab");
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId(
                "https://gitlab.internal.corp/owner/repo.git")).isEqualTo("gitlab");
    }

    @Test
    void deriveProviderId_returnsNullForUnknownHostOrBlank() {
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId(null)).isNull();
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId("")).isNull();
        assertThat(PlatformProjectMetadataAdapter.deriveProviderId(
                "https://some.other.host.example/owner/repo")).isNull();
    }

    // ── repo-full-name extraction ────────────────────────────────────────────

    @Test
    void extractRepoFullName_handlesHttpsAndSshAndStripsGitSuffix() {
        assertThat(PlatformProjectMetadataAdapter.extractRepoFullName(
                "https://github.com/owner/repo.git")).isEqualTo("owner/repo");
        assertThat(PlatformProjectMetadataAdapter.extractRepoFullName(
                "https://github.com/owner/repo")).isEqualTo("owner/repo");
        assertThat(PlatformProjectMetadataAdapter.extractRepoFullName(
                "git@github.com:owner/repo.git")).isEqualTo("owner/repo");
    }
}
