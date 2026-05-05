package com.altrix.job.adapter.in.rest;

import com.altrix.common.domain.enums.JobStatus;
import com.altrix.job.domain.port.in.JobFilter;
import com.altrix.job.adapter.out.storage.MinioJobStorageAdapter;
import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.GetJobQuery;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobQueryController {

    private final GetJobQuery           getJobQuery;
    private final MinioJobStorageAdapter minioStorage;

    @GetMapping("/{jobId}")
    public JobResponse findById(@PathVariable @NotBlank String jobId) {
        return JobResponse.from(getJobQuery.findById(jobId));
    }

    @GetMapping("/{jobId}/status")
    public JobStatusResponse getStatus(@PathVariable @NotBlank String jobId) {
        return new JobStatusResponse(jobId, getJobQuery.getStatus(jobId));
    }

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

    /**
     * GET /api/v1/jobs/{jobId}/download
     * Streams the migrated ZIP to the client.
     */
    @GetMapping("/{jobId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable @NotBlank String jobId
    ) {
        MigrationJob job = getJobQuery.findById(jobId);

        if (job.getStatus() != com.altrix.common.domain.enums.JobStatus.DONE
                || job.getOutputStorageKey() == null) {
            return ResponseEntity.badRequest().build();
        }

        StreamingResponseBody body = outputStream -> {
            try (var input = minioStorage.getMigratedZip(job.getOutputStorageKey())) {
                input.transferTo(outputStream);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"migrated-" + jobId + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
}
