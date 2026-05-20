package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.EditPlanRequest;
import com.altrix.orchestrator.adapter.in.rest.dto.MigratedFileResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.PauseRecordResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.SessionPageResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.SessionStatusResponse;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.EditPlanUseCase;
import com.altrix.orchestrator.domain.port.in.HandleApprovalUseCase;
import com.altrix.orchestrator.domain.port.in.PauseResumeSessionUseCase;
import com.altrix.orchestrator.domain.port.out.SessionPauseHistoryPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST adapter for session lifecycle operations (#64 #65 #69 #70 #72 #117 #119 #122).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/sessions}                       — paginated list with optional status filter (#117)</li>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}}           — fetch current session state</li>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}/files}     — migrated files diff (#119 #122)</li>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}/pauses}    — pause history (#72)</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/approve}   — approve plan → MIGRATING (#64) — ADMIN only</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/reject}    — reject plan → FAILED (#65) — ADMIN only</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/pause}     — pause session (#69) — ADMIN only</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/resume}    — resume session (#70) — ADMIN only</li>
 * </ul>
 *
 * <p>Domain exceptions (SessionNotFoundException, IllegalStateTransitionException,
 * OptimisticLockingFailureException) are handled globally by {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SessionController {

    private final HandleApprovalUseCase handleApproval;
    private final PauseResumeSessionUseCase pauseResume;
    private final EditPlanUseCase editPlan;
    private final WorkflowSessionRepository sessionRepository;
    private final SessionPauseHistoryPort pauseHistoryPort;

    // ── GET ───────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<SessionPageResponse> listSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) SessionStatus status) {
        boolean descending = !"asc".equalsIgnoreCase(direction);
        return ResponseEntity.ok(SessionPageResponse.from(
                sessionRepository.findAll(page, size, sortBy, descending, status)));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionStatusResponse> getSession(@PathVariable String sessionId) {
        WorkflowSession session = sessionRepository.findById(WorkflowSessionId.of(UUID.fromString(sessionId)))
                .orElseThrow(() -> new SessionNotFoundException(WorkflowSessionId.of(UUID.fromString(sessionId))));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Migrated files (#119 #122) ────────────────────────────────────────────

    @GetMapping("/{sessionId}/files")
    public ResponseEntity<List<MigratedFileResponse>> getMigratedFiles(@PathVariable String sessionId) {
        WorkflowSession session = sessionRepository.findById(WorkflowSessionId.of(UUID.fromString(sessionId)))
                .orElseThrow(() -> new SessionNotFoundException(WorkflowSessionId.of(UUID.fromString(sessionId))));
        List<MigratedFileResponse> files = session.migratedFiles().stream()
                .map(MigratedFileResponse::from)
                .toList();
        return ResponseEntity.ok(files);
    }

    /**
     * GET /api/v1/sessions/{id}/files/patch — exports all migrated files as a
     * single text/plain unified-diff blob the user can pipe through
     * {@code git apply} (#123).
     *
     * <p>Until the original-content endpoint (#119) lands the patch carries
     * the migrated content as "added" lines for every file — semantically
     * correct for {@code CREATED} files, an additions-only diff for the rest.
     * The header comment in the response makes that explicit.
     */
    @GetMapping(value = "/{sessionId}/files/patch", produces = "text/plain")
    public ResponseEntity<String> getMigratedFilesPatch(@PathVariable String sessionId) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        WorkflowSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));

        StringBuilder out = new StringBuilder();
        out.append("# Altrix migration patch — session ").append(sessionId).append('\n');
        out.append("# Note: original-side content is not yet available (#119);\n");
        out.append("# MODIFIED files are exported as additions-only diffs.\n\n");

        for (var f : session.migratedFiles()) {
            appendUnifiedDiff(out, f);
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"session-" + sessionId + ".patch\"")
                .body(out.toString());
    }

    /** Emits a minimal-but-valid unified diff block for one migrated file. */
    private static void appendUnifiedDiff(StringBuilder out, com.altrix.common.domain.model.MigratedFile f) {
        String oldPath = f.originalPath() == null || f.originalPath().isBlank() ? "/dev/null" : "a/" + f.originalPath();
        String newPath = f.newPath() == null      || f.newPath().isBlank()      ? "/dev/null" : "b/" + f.newPath();
        boolean isDelete = "DELETED".equalsIgnoreCase(String.valueOf(f.changeType()));
        boolean isCreate = "CREATED".equalsIgnoreCase(String.valueOf(f.changeType()));

        out.append("diff --git ").append(oldPath).append(' ').append(newPath).append('\n');
        out.append("--- ").append(isCreate ? "/dev/null" : oldPath).append('\n');
        out.append("+++ ").append(isDelete ? "/dev/null" : newPath).append('\n');

        String[] lines = (f.content() == null ? "" : f.content()).split("\n", -1);
        int n = lines.length;
        // Hunk header — single hunk covering the whole file
        out.append("@@ -0,0 +1,").append(n).append(" @@\n");
        char prefix = isDelete ? '-' : '+';
        for (String line : lines) {
            out.append(prefix).append(line).append('\n');
        }
        out.append('\n');
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SessionStatusResponse> approve(@PathVariable String sessionId) {
        WorkflowSession session = handleApproval.approve(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    /**
     * PATCH /api/v1/sessions/{sessionId}/plan — reviewer overrides the AI's
     * proposed plan before approving (#10 follow-up).  Allowed only at the
     * approval gate (PLAN_READY / AWAITING_APPROVAL); the next call to
     * approve runs the migrator against the edited plan automatically.
     */
    @PatchMapping("/{sessionId}/plan")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SessionStatusResponse> editSessionPlan(
            @PathVariable String sessionId,
            @Valid @RequestBody EditPlanRequest body
    ) {
        WorkflowSession session = editPlan.editPlan(toId(sessionId), body.toEditedPlan());
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    @PostMapping("/{sessionId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SessionStatusResponse> reject(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "Rejected by reviewer") : "Rejected by reviewer";
        WorkflowSession session = handleApproval.reject(toId(sessionId), reason);
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Pause / Resume (#69 #70) ──────────────────────────────────────────────

    @PostMapping("/{sessionId}/pause")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SessionStatusResponse> pause(@PathVariable String sessionId) {
        WorkflowSession session = pauseResume.pause(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    @PostMapping("/{sessionId}/resume")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SessionStatusResponse> resume(@PathVariable String sessionId) {
        WorkflowSession session = pauseResume.resume(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * DELETE /api/v1/sessions/{sessionId} — removes a workflow session row.
     * Useful for clearing terminal (DONE/FAILED) sessions or aborting stale ones.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        WorkflowSessionId id = toId(sessionId);
        sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));
        sessionRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static WorkflowSessionId toId(String raw) {
        return WorkflowSessionId.of(UUID.fromString(raw));
    }
}
