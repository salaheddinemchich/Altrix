package com.altrix.orchestrator.domain.model.user;

/**
 * Identifies an OAuth2 identity provider. Drives strategy selection at login
 * time and token lookup for provider-specific API calls (e.g. GitHub repo list).
 */
public enum AuthProviderType {
    GITHUB,
    GITLAB
}
