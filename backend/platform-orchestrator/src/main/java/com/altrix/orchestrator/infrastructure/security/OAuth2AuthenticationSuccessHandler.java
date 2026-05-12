package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.domain.port.out.UserRepository;
import com.altrix.orchestrator.domain.service.TokenService;
import com.altrix.orchestrator.infrastructure.config.JwtConfig;
import com.altrix.orchestrator.infrastructure.security.oauth.MultiProviderOAuth2UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Issues JWT access + refresh tokens after a successful OAuth2 login. Works for
 * any provider supported by {@link MultiProviderOAuth2UserService} — the
 * principal carries the resolved internal user id under
 * {@link MultiProviderOAuth2UserService#ATTR_INTERNAL_USER_ID}.
 *
 * <p>Refresh token is delivered via an {@code HttpOnly}, {@code Secure},
 * {@code SameSite=Strict} cookie so it never lands in the URL or localStorage.
 */
@Slf4j
@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider tokenProvider;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final JwtConfig jwtConfig;
    private final SecurityAuditService auditService;

    public OAuth2AuthenticationSuccessHandler(
            JwtTokenProvider tokenProvider,
            TokenService tokenService,
            UserRepository userRepository,
            JwtConfig jwtConfig,
            SecurityAuditService auditService,
            @Value("${oauth2.redirect-uri:http://localhost:4200/auth/callback}") String redirectUri) {
        this.tokenProvider  = tokenProvider;
        this.tokenService   = tokenService;
        this.userRepository = userRepository;
        this.jwtConfig      = jwtConfig;
        this.auditService   = auditService;
        setDefaultTargetUrl(redirectUri);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();

        String userId   = oauthUser.getAttribute(MultiProviderOAuth2UserService.ATTR_INTERNAL_USER_ID);
        String provider = oauthUser.getAttribute(MultiProviderOAuth2UserService.ATTR_PROVIDER);
        String login    = oauthUser.getAttribute(MultiProviderOAuth2UserService.ATTR_LOGIN);
        String email    = oauthUser.getAttribute(MultiProviderOAuth2UserService.ATTR_EMAIL);

        String role = userRepository.findById(userId)
                .map(u -> u.role().name())
                .orElse("ROLE_USER");

        String accessToken = tokenProvider.issueAccessToken(userId, login, email, role, provider);

        String rawRefreshToken = tokenProvider.issueRefreshToken(userId);
        String refreshHash     = TokenHashUtil.sha256Hex(rawRefreshToken);
        Claims refreshClaims   = tokenProvider.parse(rawRefreshToken);
        Instant expiresAt      = refreshClaims.getExpiration().toInstant();
        tokenService.storeRefreshToken(refreshHash, userId, expiresAt);

        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        String cookieValue = "refreshToken=" + rawRefreshToken +
                "; HttpOnly; Path=/api/v1/auth/refresh" +
                "; Max-Age=" + (jwtConfig.refreshTokenExpiryDays() * 86_400) +
                "; SameSite=Strict" +
                (secure ? "; Secure" : "");
        response.addHeader("Set-Cookie", cookieValue);

        auditService.loginSuccess(login != null ? login : userId, request.getRemoteAddr());
        log.debug("OAuth2 login complete: provider={} userId={} role={}", provider, userId, role);

        String redirectUrl = getDefaultTargetUrl() + "?token=" + accessToken;
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
