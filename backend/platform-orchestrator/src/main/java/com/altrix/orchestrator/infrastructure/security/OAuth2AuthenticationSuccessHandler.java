package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.domain.port.out.UserRepository;
import com.altrix.orchestrator.domain.service.TokenService;
import com.altrix.orchestrator.infrastructure.config.JwtConfig;
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
 * Issues JWT access + refresh tokens after a successful GitHub OAuth2 login.
 *
 * <p>The access token is appended as a URL fragment parameter to the frontend
 * redirect URI. The refresh token is stored in the DB and sent via a
 * {@code Set-Cookie: refreshToken=<value>; HttpOnly; Secure; SameSite=Strict}
 * response header — it never lands in the URL or localStorage.
 *
 * <p><b>Why HttpOnly cookie for refresh token?</b> HttpOnly prevents JavaScript
 * from reading the cookie, eliminating XSS-based refresh token theft.
 * SameSite=Strict prevents CSRF — a cross-site request cannot send the cookie.
 * Secure ensures the cookie is only transmitted over HTTPS.
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

        String githubId = String.valueOf(oauthUser.getAttribute("id"));
        String login    = oauthUser.getAttribute("login");
        String email    = oauthUser.getAttribute("email");

        // Look up stored role; fall back to ROLE_USER for new users
        String role = userRepository.findByGithubId(githubId)
                .map(u -> u.role().name())
                .orElse("ROLE_USER");

        // Issue short-lived access token (15 min)
        String accessToken = tokenProvider.issueAccessToken(githubId, login, email, role);

        // Issue long-lived refresh token and store its hash in the DB
        String rawRefreshToken = tokenProvider.issueRefreshToken(githubId);
        String refreshHash     = TokenHashUtil.sha256Hex(rawRefreshToken);
        Claims refreshClaims   = tokenProvider.parse(rawRefreshToken);
        Instant expiresAt      = refreshClaims.getExpiration().toInstant();
        tokenService.storeRefreshToken(refreshHash, githubId, expiresAt);

        // Refresh token in HttpOnly Secure cookie — never accessible via JS
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        String cookieValue = "refreshToken=" + rawRefreshToken +
                "; HttpOnly; Path=/api/v1/auth/refresh" +
                "; Max-Age=" + (jwtConfig.refreshTokenExpiryDays() * 86_400) +
                "; SameSite=Strict" +
                (secure ? "; Secure" : "");
        response.addHeader("Set-Cookie", cookieValue);

        auditService.loginSuccess(login, request.getRemoteAddr());
        log.debug("OAuth2 login complete: login={} role={}", login, role);

        String redirectUrl = getDefaultTargetUrl() + "?token=" + accessToken;
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

}
