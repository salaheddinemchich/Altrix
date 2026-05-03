package com.altrix.job.domain.port.out;

import java.util.Optional;

/**
 * Secondary port — fast cache for job status reads.
 * The domain never imports Redis or Lettuce.
 */
public interface JobCachePort {

    void putStatus(String jobId, String status);

    Optional<String> getStatus(String jobId);

    void evict(String jobId);
}
