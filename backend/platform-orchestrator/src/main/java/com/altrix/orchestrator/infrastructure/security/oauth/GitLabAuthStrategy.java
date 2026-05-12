package com.altrix.orchestrator.infrastructure.security.oauth;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * GitLab returns: id (integer), username, email, name, avatar_url
 * (via the /api/v4/user endpoint with scope=read_user).
 */
@Component
public class GitLabAuthStrategy implements AuthProviderStrategy {

    @Override public AuthProviderType type() { return AuthProviderType.GITLAB; }
    @Override public String registrationId() { return "gitlab"; }

    @Override
    public ExtractedIdentity extract(OAuth2User user) {
        Object idAttr = user.getAttribute("id");
        String providerId = idAttr != null ? idAttr.toString() : null;
        String username = user.getAttribute("username");
        String email = user.getAttribute("email");
        String name = user.getAttribute("name");
        String avatarUrl = user.getAttribute("avatar_url");

        return new ExtractedIdentity(
                providerId,
                username,
                email,
                name != null ? name : username,
                avatarUrl
        );
    }
}
