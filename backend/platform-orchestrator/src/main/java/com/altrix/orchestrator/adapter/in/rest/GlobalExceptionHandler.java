package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.domain.exception.IllegalStateTransitionException;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralized domain exception handler — applies to every REST controller.
 *
 * <p>Keeping exception mapping here instead of inline in each controller means:
 * <ul>
 *   <li>Every controller that throws {@link SessionNotFoundException} returns the
 *       same 404 shape without duplicating the handler.
 *   <li>New domain exceptions are wired once and immediately apply everywhere.
 *   <li>No risk of forgetting the handler in a new controller.
 * </ul>
 *
 * <p>{@link OptimisticLockingFailureException} maps to {@code 409 Conflict} — this
 * surfaces when two concurrent requests mutate the same {@code WorkflowSession} and
 * Hibernate detects a stale {@code @Version}. The client should re-fetch and retry.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSessionNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleStateConflict(IllegalStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * Optimistic lock conflict — two concurrent writes to the same session.
     * The client must re-fetch the session (which will have the latest version)
     * and retry its mutation.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Resource was modified concurrently — please retry"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid request: " + e.getMessage()));
    }
}
