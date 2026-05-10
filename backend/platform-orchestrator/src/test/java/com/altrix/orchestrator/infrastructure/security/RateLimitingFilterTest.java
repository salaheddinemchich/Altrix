package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.infrastructure.config.RateLimitConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitingFilterTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    FilterChain chain;

    RateLimitConfig config;
    RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig(10, 60, 200, 60);
        filter = new RateLimitingFilter(redis, config);
    }

    @Test
    void allowed_request_passes_through_filter_chain() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        MockHttpServletRequest req = request("GET", "/api/v1/sessions", "10.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void rate_limited_request_returns_429_and_does_not_continue() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        MockHttpServletRequest req = request("POST", "/api/v1/auth/refresh", "10.0.0.2");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    void redis_failure_is_fail_open() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis down"));

        MockHttpServletRequest req = request("GET", "/api/v1/auth/me", "10.0.0.3");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        // Fail-open: chain proceeds despite Redis being unavailable
        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void x_forwarded_for_header_is_used_as_client_ip() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        MockHttpServletRequest req = request("GET", "/api/v1/sessions", "127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        // Verify key uses the leftmost (real) IP, not the proxy IP
        verify(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> keys.stream().anyMatch(k -> k.contains("203.0.113.5"))),
                anyString(), anyString(), anyString());
    }

    @Test
    void auth_endpoint_uses_auth_rate_limit_key() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        MockHttpServletRequest req = request("POST", "/api/v1/auth/refresh", "10.0.0.5");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        verify(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> keys.stream().anyMatch(k -> k.startsWith("rl:auth:"))),
                anyString(), anyString(), anyString());
    }

    @Test
    void non_auth_endpoint_uses_api_rate_limit_key() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        MockHttpServletRequest req = request("GET", "/api/v1/sessions/123", "10.0.0.6");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        verify(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> keys.stream().anyMatch(k -> k.startsWith("rl:api:"))),
                anyString(), anyString(), anyString());
    }

    private static MockHttpServletRequest request(String method, String uri, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(remoteAddr);
        return req;
    }
}
