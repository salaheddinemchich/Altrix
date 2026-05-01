package com.migrator.project.adapter.in.rest;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.project.domain.model.Project;
import com.migrator.project.domain.port.in.GetProjectQuery;
import com.migrator.project.domain.port.in.UploadProjectUseCase;
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
    private final GetProjectQuery      getProjectQuery;

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
            @RequestPart(value = "configFormatPreference", required = false)
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
}
