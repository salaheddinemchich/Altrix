package com.altrix.job.adapter.in.rest;

import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.CreateJobUseCase;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobCommandController {

    private final CreateJobUseCase createJobUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @RequestBody CreateJobRequest request
    ) {
        MigrationJob job = createJobUseCase.createJob(
                request.projectId(),
                userId,
                null, // storageKey comes from project-registered event in normal flow
                request.configFormatPreference(),
                request.providerProfile()
        );
        return JobResponse.from(job);
    }
}
