package com.altrix.project.adapter.in.rest;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.port.in.GetProjectQuery;
import com.altrix.project.domain.port.in.IngestGitRepositoryUseCase;
import com.altrix.project.domain.port.in.IngestGitRepositoryUseCase.GitIngestionCommand;
import com.altrix.project.domain.port.in.UploadProjectUseCase;
import com.altrix.project.domain.port.out.ProjectRepositoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Primary adapter — REST controller for project operations.
 *
 * <p>This class depends only on port interfaces — never on domain services
 * or adapters directly. Spring injects the concrete implementations at runtime.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Parse and validate HTTP input</li>
 *   <li>Delegate to the use case port</li>
 *   <li>Map domain objects to HTTP response DTOs</li>
 * </ul>
 * No business logic lives here.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final UploadProjectUseCase uploadProjectUseCase;
    private final IngestGitRepositoryUseCase ingestGitRepositoryUseCase;
    private final GetProjectQuery getProjectQuery;
    private final ProjectRepositoryPort projectRepository;

    /**
     * POST /api/v1/projects/upload
     *
     * <p>Accepts a multipart ZIP file and optional metadata.
     * Returns the registered project with detection results.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse upload(
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @RequestPart("file") MultipartFile file,
            // @RequestParam (not @RequestPart) — the value is a plain form field
            // (string), so Spring binds it via the standard String→Enum converter.
            // @RequestPart on an enum requires the part to carry application/json,
            // which most multipart clients don't set on simple text parts.
            @RequestParam(value = "configFormatPreference", required = false)
            ConfigFormatPreference configFormatPreference
    ) throws IOException {

        log.info("Upload request from user '{}', file='{}'", userId, file.getOriginalFilename());

        Project project = uploadProjectUseCase.upload(
                userId,
                file.getOriginalFilename(),
                file.getInputStream(),
                file.getSize(),
                configFormatPreference
        );

        return ProjectResponse.from(project);
    }

    /**
     * POST /api/v1/projects/clone — Issue #12.
     *
     * <p>Accepts a JSON body {@link CloneProjectRequest} describing a remote Git
     * URL.  JGit clones it into a workspace, the working tree is re-packaged as
     * a ZIP and stored in MinIO, then the existing detection pipeline runs.
     *
     * <p>This endpoint coexists with {@link #upload} for now; once all callers
     * have migrated to git ingestion the legacy ZIP path will be removed.
     */
    @PostMapping(value = "/clone", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse clone(
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @Valid @RequestBody CloneProjectRequest request
    ) {
        // Never log the token, even if the caller sent one.
        log.info("Clone request from user '{}': repoUrl='{}' branch='{}' shallow={}",
                userId, request.repoUrl(), request.branch(), request.shallow());

        Project project = ingestGitRepositoryUseCase.ingestFromGit(new GitIngestionCommand(
                userId,
                request.repoUrl(),
                request.branch(),
                request.accessToken(),
                request.shallow(),
                request.configFormatPreference()
        ));

        return ProjectResponse.from(project);
    }

    /**
     * GET /api/v1/projects/{projectId}
     */
    @GetMapping("/{projectId}")
    public ProjectResponse findById(@PathVariable @NotBlank String projectId) {
        return ProjectResponse.from(getProjectQuery.findById(projectId));
    }

    /**
     * GET /api/v1/projects?userId={userId}
     */
    @GetMapping
    public List<ProjectResponse> findAllByUser(
            @RequestHeader("X-User-Id") @NotBlank String userId
    ) {
        return getProjectQuery.findAllByUserId(userId)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    /**
     * DELETE /api/v1/projects/{projectId}
     *
     * <p>Removes the project row. The user must own the project — checked by
     * matching {@code X-User-Id} to the stored {@code user_id}. Returns 404
     * if not found, 403 if owned by someone else.
     *
     * <p>Object-storage cleanup of the ZIP is best-effort — failure to remove
     * the blob from MinIO does not roll back the DB delete.
     */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @PathVariable @NotBlank String projectId
    ) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Project not found: " + projectId));

        if (!userId.equals(project.getUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Project belongs to another user");
        }

        log.info("Deleting project '{}' for user '{}'", projectId, userId);
        projectRepository.deleteById(projectId);
    }
}
