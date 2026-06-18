package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.TokenResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.UserProfileResponse;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import com.altrix.orchestrator.domain.service.TokenService;
import com.altrix.orchestrator.infrastructure.config.JwtConfig;
import com.altrix.orchestrator.infrastructure.security.JwtTokenProvider;
import com.altrix.orchestrator.infrastructure.security.TokenHashUtil;
import com.altrix.orchestrator.infrastructure.security.SecurityAuditService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Authentication REST adapter (#80).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/auth/me}      — current user profile (from JWT claims)
 *   <li>{@code POST /api/v1/auth/refresh} — rotate refresh token, issue new access token
 *   <li>{@code POST /api/v1/auth/logout}  — blacklist access token, revoke refresh tokens
 * </ul>
 *
 * <p><b>Security design:</b>
 * <ul>
 *   <li>Refresh tokens are read from HttpOnly cookies, never from the request body,
 *       to prevent XSS-based token theft.
 *   <li>Each refresh call rotates the token — the old one is immediately revoked.
 *       Replaying a revoked token triggers full family revocation (theft detection).
 *   <li>Logout blacklists the current JTI in Redis with a TTL matching the token's
 *       remaining lifetime, preventing replay during the natural expiry window.
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final JwtConfig jwtConfig;
    private final SecurityAuditService auditService;

    // ── GET /me ───────────────────────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal String userId,
                                                  HttpServletRequest request) {
        Claims claims = (Claims) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getDetails();

        return ResponseEntity.ok(new UserProfileResponse(
                userId,
                claims.get("login",    String.class),
                claims.get("email",    String.class),
                claims.get("role",     String.class),
                claims.get("provider", String.class)));
    }

    // ── POST /refresh ─────────────────────────────────────────────────────────

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawRefreshToken = extractRefreshTokenCookie(request);
        if (rawRefreshToken == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token missing"));
        }

        if (!tokenProvider.isValid(rawRefreshToken)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }

        Claims oldClaims = tokenProvider.parse(rawRefreshToken);
        if (!"refresh".equals(oldClaims.get("type", String.class))) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token type"));
        }

        String oldHash = TokenHashUtil.sha256Hex(rawRefreshToken);

        // Issue new refresh token before rotation (need the hash to record replacement)
        String newRawRefreshToken = tokenProvider.issueRefreshToken(oldClaims.getSubject());
        String newHash = TokenHashUtil.sha256Hex(newRawRefreshToken);

        Optional<String> userId = tokenService.rotateRefreshToken(oldHash, newHash);
        if (userId.isEmpty()) {
            auditService.tokenReplayDetected(oldClaims.getSubject(), request.getRemoteAddr());
            clearRefreshCookie(response);
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token invalid or revoked"));
        }

        // Store the new token in DB
        Claims newClaims = tokenProvider.parse(newRawRefreshToken);
        tokenService.storeRefreshToken(newHash, userId.get(), newClaims.getExpiration().toInstant());

        // Look up current profile
        String role = userRepository.findById(userId.get())
                .map(u -> u.role().name())
                .orElse("ROLE_USER");

        String email = userRepository.findById(userId.get())
                .map(u -> u.email() != null ? u.email() : "")
                .orElse("");

        // Provider is preserved across refresh by carrying it in the original refresh token's chain.
        // Refresh tokens themselves don't carry provider; access tokens get an empty provider on refresh.
        String newAccessToken = tokenProvider.issueAccessToken(
                userId.get(), "", email, role, "");

        // Rotate the cookie
        setRefreshCookie(response, newRawRefreshToken, request.isSecure() ||
                "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));

        auditService.tokenRefreshed(userId.get(), request.getRemoteAddr());

        return ResponseEntity.ok(TokenResponse.of(
                newAccessToken,
                tokenProvider.accessTokenExpiryMinutes(),
                role));
    }

    // ── POST /logout ──────────────────────────────────────────────────────────

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal String userId,
            HttpServletRequest request,
            HttpServletResponse response) {

        Claims claims = (Claims) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getDetails();

        long ttlSecs = tokenProvider.remainingTtlMs(claims) / 1_000;
        tokenService.logout(claims.getId(), ttlSecs, userId);
        clearRefreshCookie(response);

        auditService.logout(userId, claims.getId(), request.getRemoteAddr());

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    // ── Cookie helpers ────────────────────────────────────────────────────────

    private static String extractRefreshTokenCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> "refreshToken".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void setRefreshCookie(HttpServletResponse response, String token, boolean secure) {
        String cookieValue = "refreshToken=" + token +
                "; HttpOnly; Path=/api/v1/auth" +
                "; Max-Age=" + (jwtConfig.refreshTokenExpiryDays() * 86_400) +
                "; SameSite=Lax" +
                (secure ? "; Secure" : "");
        response.addHeader("Set-Cookie", cookieValue);
    }

    private static void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                "refreshToken=; HttpOnly; Path=/api/v1/auth; Max-Age=0; SameSite=Lax");
    }
}
