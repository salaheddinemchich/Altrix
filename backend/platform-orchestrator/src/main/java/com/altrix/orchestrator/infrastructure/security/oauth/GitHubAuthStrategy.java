package com.altrix.orchestrator.infrastructure.security.oauth;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
public class GitHubAuthStrategy implements AuthProviderStrategy {

    @Override public AuthProviderType type() { return AuthProviderType.GITHUB; }
    @Override public String registrationId() { return "github"; }

    @Override
    public ExtractedIdentity extract(OAuth2User user) {
        Object idAttr = user.getAttribute("id");
        String providerId = idAttr != null ? idAttr.toString() : null;
        String login = user.getAttribute("login");
        String email = user.getAttribute("email");
        String name = user.getAttribute("name");
        String avatarUrl = user.getAttribute("avatar_url");

        return new ExtractedIdentity(
                providerId,
                login,
                email,
                name != null ? name : login,
                avatarUrl
        );
    }
}
