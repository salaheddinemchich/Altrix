package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.ApprovalHistoryEntryResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.EditPlanRequest;
import com.altrix.orchestrator.adapter.in.rest.dto.FileDiffResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.MigratedFileResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.MigrationReportResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.MigrationReportVersionResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.RagIndexManifestResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.ShareTokenResponse;
import com.altrix.orchestrator.infrastructure.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import com.altrix.orchestrator.adapter.in.rest.dto.PauseRecordResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.SessionFileNode;
import com.altrix.orchestrator.adapter.in.rest.dto.SessionPageResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.SessionStatusResponse;
import com.altrix.orchestrator.domain.exception.SessionNotFoundException;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.in.EditPlanUseCase;
import com.altrix.orchestrator.domain.port.in.HandleApprovalUseCase;
import com.altrix.orchestrator.domain.port.in.PauseResumeSessionUseCase;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import com.altrix.orchestrator.domain.port.out.RagIndexManifestRepository;
import com.altrix.orchestrator.domain.port.out.SessionPauseHistoryPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
// All endpoints require an authenticated user. The action endpoints
// (approve, reject, pause, resume, edit plan) used to require ADMIN /
// SUPER_ADMIN; relaxed to isAuthenticated() until #25 (multi-tenancy)
// lands ownership-scoped authorization — the existing single-user
// dev workflow needs to be able to approve its own sessions.
@PreAuthorize("isAuthenticated()")
public class SessionController {

    private final HandleApprovalUseCase handleApproval;
    private final PauseResumeSessionUseCase pauseResume;
    private final EditPlanUseCase editPlan;
    private final WorkflowSessionRepository sessionRepository;
    private final SessionPauseHistoryPort pauseHistoryPort;
    private final FileReaderPort fileReader;
    private final MigrationReportRepository migrationReportRepository;
    private final RagIndexManifestRepository ragIndexManifestRepository;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Base URL the share endpoint embeds in the response so the recipient
     * can open the link directly.  Defaults to the orchestrator's own
     * port — production should override to the gateway-fronted hostname.
     */
    @Value("${app.public-base-url:http://localhost:8084}")
    private String publicBaseUrl;

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

    /**
     * GET /api/v1/sessions/by-job/{jobId} — direct lookup of the session driving
     * a given migration job.  Avoids the previous frontend pattern of fetching
     * page 0 of the sessions list and hoping the wanted session was the most
     * recent one — that pattern silently returned the wrong session whenever
     * the user had any newer job/session.
     */
    @GetMapping("/by-job/{jobId}")
    public ResponseEntity<SessionStatusResponse> getSessionByJobId(@PathVariable String jobId) {
        return sessionRepository.findByJobId(jobId)
                .map(SessionStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
     * GET /api/v1/sessions/{id}/files/tree — returns every file in the source
     * ZIP plus any new file created by the migration, each tagged with a
     * change-type status.  Drives the project-wide file tree on the left
     * side of the diff viewer so the reviewer sees the architecture (not
     * just touched files).
     *
     * <p>Status values:
     * <ul>
     *   <li>{@code MODIFIED}  — migrator changed the file</li>
     *   <li>{@code CREATED}   — file did not exist in source, added by migration</li>
     *   <li>{@code DELETED}   — file existed in source but is removed in output</li>
     *   <li>{@code UNCHANGED} — migrator examined the file and kept it as-is</li>
     *   <li>{@code UNTOUCHED} — file existed in source; pruner excluded it from migration</li>
     * </ul>
     */
    @GetMapping("/{sessionId}/files/tree")
    public ResponseEntity<List<SessionFileNode>> getFileTree(@PathVariable String sessionId) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        WorkflowSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));

        // path -> change type from the migration result
        Map<String, String> migratedStatus = session.migratedFiles().stream()
                .collect(java.util.stream.Collectors.toMap(
                        f -> f.originalPath() != null && !f.originalPath().isBlank()
                                ? f.originalPath() : f.newPath(),
                        f -> String.valueOf(f.changeType()),
                        (a, b) -> a));

        // Full set of paths from the original source ZIP
        java.util.Set<String> sourcePaths = (session.plan() != null
                && session.plan().storageKey() != null
                && !session.plan().storageKey().isBlank())
                ? fileReader.listAllPaths(session.plan().storageKey())
                : java.util.Set.of();

        List<SessionFileNode> tree = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (String path : sourcePaths) {
            String status = migratedStatus.getOrDefault(path, "UNTOUCHED");
            tree.add(new SessionFileNode(path, status));
            seen.add(path);
        }

        // CREATED files that weren't in source
        for (var f : session.migratedFiles()) {
            String key = f.newPath() != null ? f.newPath() : f.originalPath();
            if (!seen.contains(key) && "CREATED".equalsIgnoreCase(String.valueOf(f.changeType()))) {
                tree.add(new SessionFileNode(key, "CREATED"));
            }
        }

        tree.sort(java.util.Comparator.comparing(SessionFileNode::path));
        return ResponseEntity.ok(tree);
    }

    /**
     * GET /api/v1/sessions/{id}/files/diff?path=<originalPath> — returns both
     * sides of the diff for a single file (#119).  Powers the Monaco diff
     * viewer's left pane.  originalContent comes from MinIO via
     * {@link FileReaderPort#readSingleFile(String, String)} using the
     * storageKey on the session's plan.
     */
    @GetMapping("/{sessionId}/files/diff")
    public ResponseEntity<FileDiffResponse> getFileDiff(
            @PathVariable String sessionId,
            @RequestParam String path
    ) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        WorkflowSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));

        var migrated = session.migratedFiles().stream()
                .filter(f -> path.equals(f.originalPath()) || path.equals(f.newPath()))
                .findFirst()
                .orElse(null);

        if (migrated == null) {
            return ResponseEntity.notFound().build();
        }

        // CREATED files have no original; DELETED files have no migrated.
        boolean isCreated = "CREATED".equalsIgnoreCase(String.valueOf(migrated.changeType()));
        boolean isDeleted = "DELETED".equalsIgnoreCase(String.valueOf(migrated.changeType()));

        String originalContent = null;
        if (!isCreated && session.plan() != null && session.plan().storageKey() != null) {
            originalContent = fileReader.readSingleFile(session.plan().storageKey(), migrated.originalPath());
        }

        return ResponseEntity.ok(new FileDiffResponse(
                migrated.originalPath(),
                migrated.newPath(),
                migrated.changeType(),
                originalContent,
                isDeleted ? null : migrated.content(),
                migrated.diffSummary()
        ));
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

    // ── Migration report (#129) ───────────────────────────────────────────────

    /**
     * GET /api/v1/sessions/{id}/report — returns the persisted markdown
     * migration report produced by Agent 5 once the session is DONE.
     * 404 when the report doesn't exist yet (session is mid-pipeline or
     * predates report persistence).
     */
    @GetMapping("/{sessionId}/report")
    public ResponseEntity<MigrationReportResponse> getMigrationReport(@PathVariable String sessionId) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        return migrationReportRepository.findBySessionId(id)
                .map(MigrationReportResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/sessions/{id}/reports — full append-only history of
     * report versions, newest first (#162).  Empty list when nothing has
     * been generated yet.
     */
    @GetMapping("/{sessionId}/reports")
    public ResponseEntity<List<MigrationReportVersionResponse>> listMigrationReports(@PathVariable String sessionId) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        List<MigrationReportVersionResponse> history = migrationReportRepository.findAllBySessionId(id)
                .stream()
                .map(MigrationReportVersionResponse::from)
                .toList();
        return ResponseEntity.ok(history);
    }

    /**
     * GET /api/v1/sessions/{id}/reports/{version} — fetch a specific
     * version (#162).  404 when the version doesn't exist.
     */
    @GetMapping("/{sessionId}/reports/{version}")
    public ResponseEntity<MigrationReportVersionResponse> getMigrationReportVersion(
            @PathVariable String sessionId,
            @PathVariable int version
    ) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        return migrationReportRepository.findBySessionIdAndVersion(id, version)
                .map(MigrationReportVersionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/sessions/{id}/reports/{version}/share — generates a
     * stateless, time-limited public link to the given report version
     * (#133).  The token is a JWT signed with the same RS256 key used
     * for access tokens; the public endpoint verifies signature + expiry
     * + that the {@code type} claim is {@code "share"}.  Tokens can't be
     * revoked individually — the TTL caps the blast radius of a leak.
     *
     * @param ttlMinutes desired lifetime in minutes; clamped server-side
     *                   to {@code [5, 30*24*60]} (5 min – 30 days).
     *                   Default 24 h.
     */
    @PostMapping("/{sessionId}/reports/{version}/share")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ShareTokenResponse> shareReportVersion(
            @PathVariable String sessionId,
            @PathVariable int version,
            @RequestParam(defaultValue = "1440") long ttlMinutes
    ) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        // 404 if the version doesn't exist — don't issue tokens to nothing.
        if (migrationReportRepository.findBySessionIdAndVersion(id, version).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String token = jwtTokenProvider.issueShareToken(sessionId, version, ttlMinutes);
        long clampedMinutes = Math.max(
                JwtTokenProvider.MIN_SHARE_TTL_MINUTES,
                Math.min(ttlMinutes, JwtTokenProvider.MAX_SHARE_TTL_MINUTES));
        return ResponseEntity.ok(new ShareTokenResponse(
                token,
                publicBaseUrl + "/api/v1/public/reports/" + token,
                java.time.Instant.now().plusSeconds(clampedMinutes * 60)
        ));
    }

    // ── RAG index manifest ────────────────────────────────────────────────────

    /**
     * GET /api/v1/sessions/{id}/rag-index — returns the manifest of source
     * files that were indexed for RAG retrieval during this session.
     *
     * <p>404 when no manifest has been persisted yet (session predates the
     * feature, or indexing crashed before the persist call).  The Index
     * step on the JobDetail timeline uses this to render the
     * "view indexed files" expansion.
     */
    @GetMapping("/{sessionId}/rag-index")
    public ResponseEntity<RagIndexManifestResponse> getRagIndexManifest(@PathVariable String sessionId) {
        WorkflowSessionId id = WorkflowSessionId.of(UUID.fromString(sessionId));
        return ragIndexManifestRepository.findBySessionId(id)
                .map(RagIndexManifestResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Approval history (#126) ───────────────────────────────────────────────

    /**
     * GET /api/v1/sessions/{id}/approval/history — returns the timeline of
     * approve/reject decisions on this session.  Currently zero or one entry
     * because the workflow doesn't yet support re-approval after a plan edit;
     * the response is a list so the contract stays stable when it does.
     */
    @GetMapping("/{sessionId}/approval/history")
    public ResponseEntity<List<ApprovalHistoryEntryResponse>> getApprovalHistory(@PathVariable String sessionId) {
        WorkflowSession session = sessionRepository.findById(WorkflowSessionId.of(UUID.fromString(sessionId)))
                .orElseThrow(() -> new SessionNotFoundException(WorkflowSessionId.of(UUID.fromString(sessionId))));
        if (session.decisionKind() == null) {
            return ResponseEntity.ok(List.of());
        }
        String reason = session.decisionKind().name().equals("REJECTED") ? session.errorMessage() : null;
        return ResponseEntity.ok(List.of(new ApprovalHistoryEntryResponse(
                session.decidedBy(),
                session.decidedAt(),
                session.decisionKind().name(),
                reason
        )));
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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionStatusResponse> approve(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String userId) {
        // userId is the JWT sub claim (the user's stable id).  Falls back to
        // "anonymous" only when called from a non-auth context (which the
        // @PreAuthorize above rules out, but the null-check is cheap).
        String decidedBy = userId != null && !userId.isBlank() ? userId : "anonymous";
        WorkflowSession session = handleApproval.approve(toId(sessionId), decidedBy);
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    /**
     * PATCH /api/v1/sessions/{sessionId}/plan — reviewer overrides the AI's
     * proposed plan before approving (#10 follow-up).  Allowed only at the
     * approval gate (PLAN_READY / AWAITING_APPROVAL); the next call to
     * approve runs the migrator against the edited plan automatically.
     */
    @PatchMapping("/{sessionId}/plan")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionStatusResponse> editSessionPlan(
            @PathVariable String sessionId,
            @Valid @RequestBody EditPlanRequest body
    ) {
        WorkflowSession session = editPlan.editPlan(toId(sessionId), body.toEditedPlan());
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    @PostMapping("/{sessionId}/reject")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionStatusResponse> reject(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        String reason = body != null ? body.getOrDefault("reason", "Rejected by reviewer") : "Rejected by reviewer";
        String decidedBy = userId != null && !userId.isBlank() ? userId : "anonymous";
        WorkflowSession session = handleApproval.reject(toId(sessionId), reason, decidedBy);
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    // ── Pause / Resume (#69 #70) ──────────────────────────────────────────────

    @PostMapping("/{sessionId}/pause")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionStatusResponse> pause(@PathVariable String sessionId) {
        WorkflowSession session = pauseResume.pause(toId(sessionId));
        return ResponseEntity.ok(SessionStatusResponse.from(session));
    }

    @PostMapping("/{sessionId}/resume")
    @PreAuthorize("isAuthenticated()")
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
