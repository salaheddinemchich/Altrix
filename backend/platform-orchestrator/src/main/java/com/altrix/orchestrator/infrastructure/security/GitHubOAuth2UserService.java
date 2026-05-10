package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.domain.model.user.User;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Upserts the authenticated GitHub user into the local users table (#81).
 * The GitHub access token is stored AES-256-GCM encrypted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final ApiKeyEncryptionPort encryption;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oauthUser = super.loadUser(request);

        String githubId = String.valueOf(oauthUser.getAttribute("id"));
        String login = oauthUser.getAttribute("login");
        String email = oauthUser.getAttribute("email");
        String name = oauthUser.getAttribute("name");
        String avatarUrl = oauthUser.getAttribute("avatar_url");

        String rawToken = request.getAccessToken().getTokenValue();
        String encryptedToken = encrypt(rawToken);

        User user = User.create(githubId, login, email, name != null ? name : login, avatarUrl);
        userRepository.upsert(user, encryptedToken);

        log.debug("Upserted GitHub user: login={}", login);
        return oauthUser;
    }

    private String encrypt(String token) {
        try {
            return encryption.encrypt(token);
        } catch (Exception e) {
            log.warn("Failed to encrypt GitHub access token — storing blank", e);
            return "";
        }
    }
}
