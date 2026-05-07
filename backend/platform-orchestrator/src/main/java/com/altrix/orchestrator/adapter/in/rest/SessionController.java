package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.PauseRecordResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.SessionStatusResponse;
import com.altrix.orchestrator.domain.exception.IllegalStateTransitionException;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.HandleApprovalUseCase;
import com.altrix.orchestrator.domain.port.in.PauseResumeSessionUseCase;
import com.altrix.orchestrator.domain.port.out.SessionPauseHistoryPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST adapter for session lifecycle operations (#64 #65 #69 #70 #72).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}}           — fetch current session state</li>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}/pauses}    — pause history (#72)</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/approve}   — approve plan → MIGRATING (#64)</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/reject}    — reject plan → FAILED (#65)</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/pause}     — pause session (#69)</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/resume}    — resume session (#70)</li>
 * </ul>
 *
 * <p>State-transition conflicts return {@code 409 Conflict}.
 * Missing sessions return {@code 404 Not Found}.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final HandleApprovalUseCase handleApproval;
    private final PauseResumeSessionUseCase pauseResume;
    private final WorkflowSessionRepository sessionRepository;
    private final SessionPauseHistoryPort pauseHistoryPort;

    // ── GET ───────────────────────────────────────────────────────────────────

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionStatusResponse> getSession(@PathVariable String sessionId) {
        WorkflowSession session = sessionRepository.findById(WorkflowSessionId.of(UUID.fromString(sessionId)))
                .orElseThrow(() -> new SessionNotFoundException(WorkflowSessionId.of(UUID.fromString(sessionId))));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Pause history (#72) ───────────────────────────────────────────────────

    @GetMapping("/{sessionId}/pauses")
    public ResponseEntity<List<PauseRecordResponse>> getPauseHistory(@PathVariable String sessionId) {
        List<PauseRecordResponse> history = pauseHistoryPort
                .findBySessionId(toId(sessionId))
                .stream()
                .map(PauseRecordResponse::from)
                .toList();
        return ResponseEntity.ok(history);
    }

    // ── Approval (#64 #65) ────────────────────────────────────────────────────

    @PostMapping("/{sessionId}/approve")
    public ResponseEntity<SessionStatusResponse> approve(@PathVariable String sessionId) {
        WorkflowSession session = handleApproval.approve(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    @PostMapping("/{sessionId}/reject")
    public ResponseEntity<SessionStatusResponse> reject(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "Rejected by reviewer") : "Rejected by reviewer";
        WorkflowSession session = handleApproval.reject(toId(sessionId), reason);
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Pause / Resume (#69 #70) ──────────────────────────────────────────────

    @PostMapping("/{sessionId}/pause")
    public ResponseEntity<SessionStatusResponse> pause(@PathVariable String sessionId) {
        WorkflowSession session = pauseResume.pause(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<SessionStatusResponse> resume(@PathVariable String sessionId) {
        WorkflowSession session = pauseResume.resume(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Exception mapping ─────────────────────────────────────────────────────

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateTransitionException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("error", "Invalid session ID format"));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static WorkflowSessionId toId(String raw) {
        return WorkflowSessionId.of(UUID.fromString(raw));
    }
}
