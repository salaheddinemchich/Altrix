package com.altrix.job.domain.port.in;

import com.altrix.common.domain.enums.JobStatus;
import lombok.Builder;

import java.time.Instant;

/**
 * Immutable filter DTO for job list queries.
 * Every field is optional — null means "no filter on this field".
 */
@Builder
public record JobFilter(
        String userId,
        String projectId,
        JobStatus status,
        Instant createdAfter,
        Instant createdBefore
) {}
