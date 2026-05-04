package com.altrix.job.adapter.out.persistence.spec;

import com.altrix.common.domain.enums.JobStatus;
import lombok.Builder;

import java.time.Instant;

/**
 * Immutable filter DTO used to build Criteria API Specifications.
 *
 * <p>Every field is optional — null means "no filter on this field".
 * The Specification builder combines only non-null fields.
 */
@Builder
public record JobFilter(
        String userId,
        String projectId,
        JobStatus status,
        Instant createdAfter,
        Instant createdBefore
) {}
