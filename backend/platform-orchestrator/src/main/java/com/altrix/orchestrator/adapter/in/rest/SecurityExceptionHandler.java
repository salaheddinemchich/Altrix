package com.altrix.orchestrator.adapter.in.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralized security exception handler.
 *
 * <p><b>Why generic error messages?</b> Detailed authentication error messages are a
 * security anti-pattern — they reveal information useful to attackers
 * (e.g., "User not found" confirms a valid username; "Wrong password" confirms the user exists).
 * All auth failures return the same opaque message regardless of root cause.
 *
 * <p><b>No stack traces in responses.</b> Internal stack traces can reveal framework versions,
 * class names, and vulnerability details. All exceptions are logged server-side with full
 * context but the client receives only a generic error object.
 *
 * <p>Correlation ID support: extend this handler to include a request-scoped trace ID
 * (e.g., from MDC) in the response so clients can reference a specific error in support requests
 * without the server disclosing any sensitive information.
 */
@Slf4j
@RestControllerAdvice
public class SecurityExceptionHandler {

    /** JWT missing, expired, or invalid signature — 401. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationException(AuthenticationException e) {
        log.debug("Authentication failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
    }

    /** Valid JWT but insufficient role for the resource — 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        log.debug("Access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Access denied"));
    }

    /** Unauthenticated request hitting a secured endpoint — 401. */
    @ExceptionHandler(InsufficientAuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientAuth(InsufficientAuthenticationException e) {
        log.debug("Insufficient authentication: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required"));
    }
}
