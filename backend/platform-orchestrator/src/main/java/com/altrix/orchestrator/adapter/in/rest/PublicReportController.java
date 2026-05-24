package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.MigrationReportVersionResponse;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import com.altrix.orchestrator.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, unauthenticated entry point for shareable report links (#133).
 *
 * <p>The path is in the {@code /api/v1/public/**} space which
 * {@link com.altrix.orchestrator.infrastructure.config.SecurityConfig}
 * grants {@code permitAll} — auth instead is carried INSIDE the URL as
 * a signed JWT (type=share).  We validate the token, look up the
 * version it points to, and return the same DTO the authenticated
 * endpoint serves.  No PII or session metadata leaks beyond the report
 * markdown itself.
 *
 * <p>Tokens cannot be revoked individually; the TTL chosen at issuance
 * (5 min – 30 days) is the only way to expire them.  This is a
 * deliberate stateless trade-off: zero DB writes per share, zero
 * stateful tracking, simple to operate.  Sensitive reports should
 * use short TTLs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicReportController {

    private final JwtTokenProvider jwtTokenProvider;
    private final MigrationReportRepository migrationReportRepository;

    @GetMapping("/reports/{token}")
    public ResponseEntity<MigrationReportVersionResponse> fetchSharedReport(@PathVariable String token) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseShareToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            // Invalid signature, expired, or wrong type — single 404 response
            // to avoid leaking whether the token is malformed vs. expired vs.
            // pointing at a non-existent version.
            log.debug("Share token rejected: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }

        String sessionIdStr = (String) claims.get("sessionId");
        Object versionRaw   = claims.get("version");
        if (sessionIdStr == null || versionRaw == null) {
            return ResponseEntity.notFound().build();
        }

        WorkflowSessionId id;
        int version;
        try {
            id = WorkflowSessionId.of(UUID.fromString(sessionIdStr));
            version = ((Number) versionRaw).intValue();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        return migrationReportRepository.findBySessionIdAndVersion(id, version)
                .map(MigrationReportVersionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
