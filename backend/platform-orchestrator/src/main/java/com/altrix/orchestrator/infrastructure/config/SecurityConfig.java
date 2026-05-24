package com.altrix.orchestrator.infrastructure.config;

import com.altrix.orchestrator.infrastructure.security.JwtAuthenticationFilter;
import com.altrix.orchestrator.infrastructure.security.OAuth2AuthenticationSuccessHandler;
import com.altrix.orchestrator.infrastructure.security.RateLimitingFilter;
import com.altrix.orchestrator.infrastructure.security.oauth.MultiProviderOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Master Spring Security configuration.
 *
 * <p>Security decisions explained:
 * <ul>
 *   <li><b>CSRF disabled</b> — safe for stateless REST APIs that never use cookie-based sessions.
 *       Tokens are sent in the {@code Authorization} header which is not auto-submitted by browsers,
 *       so CSRF is not a threat. Enabling CSRF on a stateless API would only add latency.
 *   <li><b>STATELESS sessions</b> — no server-side session is ever created. All state lives in
 *       signed JWT tokens. This enables horizontal scaling without sticky sessions.
 *   <li><b>Security headers</b> — HSTS prevents protocol downgrade attacks; CSP restricts
 *       script execution; X-Frame-Options prevents clickjacking; X-Content-Type-Options prevents
 *       MIME sniffing exploits; Referrer-Policy limits information leakage.
 *   <li><b>Method security</b> — {@code @EnableMethodSecurity} allows {@code @PreAuthorize} on
 *       service/controller methods for fine-grained RBAC beyond URL patterns.
 *   <li><b>Filter order</b> — RateLimitingFilter runs first (before JWT validation) so that
 *       malformed token floods are rejected cheaply without touching the DB or RSA operations.
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MultiProviderOAuth2UserService oauth2UserService;
    private final OAuth2AuthenticationSuccessHandler successHandler;
    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitConfig rateLimitConfig;

    @Value("${cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ── CSRF: disabled because API is stateless (JWT in header, not cookie) ──
            .csrf(AbstractHttpConfigurer::disable)

            // ── Session: stateless — no HttpSession is ever created ──────────────
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── CORS: delegated to WebMvcConfig (single source of truth) ─────────
            .cors(Customizer.withDefaults())

            // ── Security Headers ──────────────────────────────────────────────────
            .headers(headers -> headers
                // HSTS: force HTTPS for 1 year, include subdomains, allow preload list submission
                // Prevents protocol-downgrade (SSL stripping) attacks
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000)
                    .preload(true)
                )
                // CSP: only allow scripts/styles from same origin; block inline scripts
                // Mitigates XSS by preventing injected scripts from executing
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: https:; " +
                    "font-src 'self'; " +
                    "connect-src 'self'; " +
                    "frame-ancestors 'none'"
                ))
                // Prevent clickjacking — page cannot be embedded in a frame
                .frameOptions(frame -> frame.deny())
                // Prevent MIME-type sniffing (e.g., serving JS as text/plain to bypass CSP)
                .contentTypeOptions(Customizer.withDefaults())
                // Limit Referer header to same-origin — prevents leaking URLs to third parties
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Disable dangerous browser features not needed by this API
                .permissionsPolicy(pp -> pp.policy(
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
                ))
            )

            // ── URL-level access rules ────────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/oauth2/**",
                    "/login/**",
                    "/api/v1/auth/refresh",
                    "/actuator/health",
                    "/actuator/info",
                    // SockJS handshake at /ws/info has no JWT (the token lives
                    // on the STOMP CONNECT frame, not the HTTP handshake).  If
                    // we don't permit this path the SockJS negotiation gets
                    // 401'd before STOMP can even start, the WebSocket stays
                    // dead, and the JobDetail timeline never receives live
                    // progress events.  Authorization on subscriptions can be
                    // added later via a StompChannelInterceptor.
                    "/ws/**",
                    // #133 — public shareable report links.  Auth is carried
                    // INSIDE the URL itself as a signed JWT (type=share).
                    // Endpoint handler verifies the token before returning.
                    "/api/v1/public/**"
                ).permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )

            // ── OAuth2 login (GitHub + GitLab via strategy pattern) ──────────────
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(u -> u.userService(oauth2UserService))
                .successHandler(successHandler)
            )

            // ── Auth failure handling ────────────────────────────────────────────
            // For /api/** return 401 so XHR clients can prompt re-login. Without
            // this, Spring Security falls back to the OAuth2 login entry point and
            // 302s to /login — which the browser refuses to follow cross-origin
            // and Angular surfaces as a useless `status: 0` error. /oauth2/** keeps
            // the default redirect entry point so the GitHub/GitLab login flow
            // continues to work for browser navigation.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")
                )
            )

            // ── Filter chain order ────────────────────────────────────────────────
            // 1. Rate limiter (cheapest — rejects floods before any processing)
            .addFilterBefore(rateLimitingFilter(), UsernamePasswordAuthenticationFilter.class)
            // 2. JWT validator (validates signature + blacklist check)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter() {
        return new RateLimitingFilter(stringRedisTemplate, rateLimitConfig);
    }
}
