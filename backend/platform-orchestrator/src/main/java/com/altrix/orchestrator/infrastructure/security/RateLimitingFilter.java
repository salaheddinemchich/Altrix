package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.infrastructure.config.RateLimitConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Redis sliding-window rate limiter applied before authentication.
 *
 * <p><b>Algorithm:</b> a sorted set stores request timestamps. On each request:
 * <ol>
 *   <li>Remove entries older than the window.
 *   <li>Count remaining entries.
 *   <li>If count ≥ limit → reject with 429.
 *   <li>Otherwise add current timestamp and set key TTL.
 * </ol>
 * All four operations are executed atomically in a single Lua script to prevent
 * race conditions (TOCTOU) in distributed deployments.
 *
 * <p><b>Why per-IP and not per-user?</b> Auth endpoints are hit before a valid
 * JWT exists, so IP is the only available key. General API endpoints also use IP
 * to stay stateless; authenticated rate limiting is layered on top by the domain
 * services themselves when finer control is needed.
 *
 * <p><b>Fail-open policy:</b> if Redis is unavailable, requests are allowed through.
 * Auth endpoints are also protected by the brute-force lockout which uses the same
 * Redis instance — both being down simultaneously means the system is degraded
 * anyway and availability takes priority.
 */
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    /**
     * Atomic sliding-window Lua script.
     * KEYS[1] = Redis key, ARGV[1] = now (ms), ARGV[2] = window (ms), ARGV[3] = limit
     * Returns 1 if allowed, 0 if rate-limited.
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT =
            new DefaultRedisScript<>("""
                    local key    = KEYS[1]
                    local now    = tonumber(ARGV[1])
                    local window = tonumber(ARGV[2])
                    local limit  = tonumber(ARGV[3])
                    local cutoff = now - window
                    redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
                    local count = redis.call('ZCARD', key)
                    if count >= limit then
                        return 0
                    end
                    redis.call('ZADD', key, now, now)
                    redis.call('PEXPIRE', key, window)
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitConfig config;

    public RateLimitingFilter(StringRedisTemplate redis, RateLimitConfig config) {
        this.redis = redis;
        this.config = config;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String ip = resolveClientIp(request);
        String path = request.getRequestURI();
        boolean isAuthPath = isAuthEndpoint(path);

        int limit   = isAuthPath ? config.authMaxRequests()   : config.apiMaxRequests();
        int window  = isAuthPath ? config.authWindowSeconds() : config.apiWindowSeconds();
        String key  = "rl:" + (isAuthPath ? "auth" : "api") + ":" + ip;

        if (!isAllowed(key, limit, window)) {
            log.warn("Rate limit exceeded for IP={} path={}", ip, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(window));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too many requests\",\"retryAfterSeconds\":" + window + "}");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAllowed(String key, int limit, int windowSeconds) {
        try {
            long nowMs   = System.currentTimeMillis();
            long windowMs = (long) windowSeconds * 1_000;
            Long result = redis.execute(SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(limit));
            return !Long.valueOf(0).equals(result);
        } catch (Exception e) {
            log.error("Rate-limit Redis call failed — fail-open: {}", e.getMessage());
            return true; // fail-open
        }
    }

    private static boolean isAuthEndpoint(String path) {
        return path.startsWith("/oauth2/") ||
               path.startsWith("/login/") ||
               path.startsWith("/api/v1/auth/");
    }

    /** Respects X-Forwarded-For set by a trusted reverse proxy. */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim(); // leftmost = original client
        }
        return request.getRemoteAddr();
    }
}
