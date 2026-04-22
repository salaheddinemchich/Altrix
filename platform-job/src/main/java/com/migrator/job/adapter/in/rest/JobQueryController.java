package com.migrator.job.adapter.in.rest;

import com.migrator.common.domain.enums.JobStatus;
import com.migrator.job.adapter.out.persistence.spec.JobFilter;
import com.migrator.job.domain.port.in.GetJobQuery;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Primary adapter — handles QUERY (read) operations.
 * Completely separate from {@link JobCommandController}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobQueryController {

    private final GetJobQuery getJobQuery;

    /** GET /api/v1/jobs/{jobId} */
    @GetMapping("/{jobId}")
    public JobResponse findById(@PathVariable @NotBlank String jobId) {
        return JobResponse.from(getJobQuery.findById(jobId));
    }

    /** GET /api/v1/jobs/{jobId}/status — fast Redis read */
    @GetMapping("/{jobId}/status")
    public JobStatusResponse getStatus(@PathVariable @NotBlank String jobId) {
        return new JobStatusResponse(jobId, getJobQuery.getStatus(jobId));
    }

    /** GET /api/v1/jobs?userId=&status= */
    @GetMapping
    public List<JobResponse> findAll(
            @RequestHeader("X-User-Id") @NotBlank String userId,
            @RequestParam(required = false) JobStatus status
    ) {
        JobFilter filter = JobFilter.builder()
                .userId(userId)
                .status(status)
                .build();
        return getJobQuery.findAll(filter)
                .stream()
                .map(JobResponse::from)
                .toList();
    }
}
