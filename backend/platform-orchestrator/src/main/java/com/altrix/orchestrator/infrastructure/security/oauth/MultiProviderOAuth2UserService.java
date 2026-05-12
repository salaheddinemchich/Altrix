package com.altrix.orchestrator.infrastructure.security.oauth;

import com.altrix.orchestrator.domain.model.user.User;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single OAuth2 user service that dispatches to a provider-specific
 * {@link AuthProviderStrategy} based on the {@code registrationId} of the request.
 *
 * <p>After delegation, the resolved internal {@link User} is exposed on the
 * returned principal under the attribute {@code "altrix.user.id"} and the
 * principal's name. Downstream handlers (e.g. JWT issuer) read those.
 */
@Slf4j
@Component
public class MultiProviderOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String ATTR_INTERNAL_USER_ID = "altrix.user.id";
    public static final String ATTR_PROVIDER         = "altrix.provider";
    public static final String ATTR_LOGIN            = "altrix.login";
    public static final String ATTR_EMAIL            = "altrix.email";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final Map<String, AuthProviderStrategy> strategiesByRegistration;
    private final UserRepository userRepository;
    private final ApiKeyEncryptionPort encryption;

    public MultiProviderOAuth2UserService(List<AuthProviderStrategy> strategies,
                                          UserRepository userRepository,
                                          ApiKeyEncryptionPort encryption) {
        this.userRepository = userRepository;
        this.encryption = encryption;
        Map<String, AuthProviderStrategy> map = new HashMap<>();
        for (AuthProviderStrategy s : strategies) map.put(s.registrationId(), s);
        this.strategiesByRegistration = Map.copyOf(map);
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User providerUser = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();

        AuthProviderStrategy strategy = strategiesByRegistration.get(registrationId);
        if (strategy == null) {
            throw new OAuth2AuthenticationException("No AuthProviderStrategy registered for: " + registrationId);
        }

        AuthProviderStrategy.ExtractedIdentity identity = strategy.extract(providerUser);
        String encryptedToken = encryptToken(request.getAccessToken().getTokenValue());

        User user = userRepository.upsertWithProvider(
                strategy.type(),
                identity.providerId(),
                identity.providerLogin(),
                identity.email(),
                identity.displayName(),
                identity.avatarUrl(),
                encryptedToken
        );

        log.debug("OAuth2 login via {} resolved to internal userId={}", registrationId, user.id());

        Map<String, Object> attributes = new HashMap<>(providerUser.getAttributes());
        attributes.put(ATTR_INTERNAL_USER_ID, user.id());
        attributes.put(ATTR_PROVIDER, strategy.type().name());
        attributes.put(ATTR_LOGIN, identity.providerLogin());
        attributes.put(ATTR_EMAIL, identity.email());

        Set<? extends GrantedAuthority> authorities = Set.copyOf(providerUser.getAuthorities());
        return new DefaultOAuth2User(authorities, attributes, ATTR_INTERNAL_USER_ID);
    }

    private String encryptToken(String token) {
        try { return encryption.encrypt(token); }
        catch (Exception e) {
            log.warn("Failed to encrypt OAuth access token — storing blank", e);
            return "";
        }
    }
}
