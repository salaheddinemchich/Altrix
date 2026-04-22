package com.migrator.job.adapter.in.rest;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import com.migrator.job.domain.model.MigrationJob;
import com.migrator.job.domain.port.in.CreateJobUseCase;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Primary adapter — handles COMMAND (write) operations.
 * Completely separate from {@link JobQueryController}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobCommandController {

    private final CreateJobUseCase createJobUseCase;

    /**
     * POST /api/v1/jobs
     * Manually trigger a migration job for an already-registered project.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @RequestBody CreateJobRequest request
    ) {
        MigrationJob job = createJobUseCase.createJob(
                request.projectId(),
                userId,
                request.configFormatPreference()
        );
        return JobResponse.from(job);
    }
}
