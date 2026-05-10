package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.domain.port.out.TokenBlacklistPort;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the {@code Authorization: Bearer <token>} header on every request.
 *
 * <p>Security checks in order:
 * <ol>
 *   <li>Token passes RS256 signature + expiry verification.
 *   <li>Token {@code type} claim equals {@code access} — refresh tokens are rejected.
 *   <li>Token {@code jti} is not in the Redis blacklist (prevents replay after logout).
 * </ol>
 *
 * <p>The {@code role} claim is mapped to a {@link SimpleGrantedAuthority},
 * enabling URL-level and method-level RBAC via {@code @PreAuthorize}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklistPort tokenBlacklist;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            authenticate(bearer.substring(7));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        if (!tokenProvider.isValid(token)) return;

        Claims claims = tokenProvider.parse(token);

        // Prevent refresh tokens from being used as bearer credentials
        if (!"access".equals(claims.get("type", String.class))) {
            log.debug("Rejected non-access token as bearer credential jti={}", claims.getId());
            return;
        }

        // Prevent replayed tokens from revoked sessions (logout / token rotation)
        if (tokenBlacklist.isBlacklisted(claims.getId())) {
            log.debug("Rejected blacklisted token jti={}", claims.getId());
            return;
        }

        String role = claims.get("role", String.class);
        if (role == null || role.isBlank()) role = "ROLE_USER";

        var auth = new UsernamePasswordAuthenticationToken(
                claims.getSubject(), null,
                List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
