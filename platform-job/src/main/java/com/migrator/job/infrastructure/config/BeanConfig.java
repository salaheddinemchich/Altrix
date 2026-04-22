package com.migrator.job.infrastructure.config;

import com.migrator.job.domain.port.out.JobCachePort;
import com.migrator.job.domain.port.out.JobEventPublisher;
import com.migrator.job.domain.port.out.JobRepository;
import com.migrator.job.domain.service.JobCommandService;
import com.migrator.job.domain.service.JobQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires domain services to their adapter implementations.
 * Domain services have no Spring annotations — pure Java.
 */
@Configuration
public class BeanConfig {

    @Bean
    public JobCommandService jobCommandService(
            JobRepository    jobRepository,
            JobCachePort     jobCachePort,
            JobEventPublisher jobEventPublisher
    ) {
        return new JobCommandService(jobRepository, jobCachePort, jobEventPublisher);
    }

    @Bean
    public JobQueryService jobQueryService(
            JobRepository jobRepository,
            JobCachePort  jobCachePort
    ) {
        return new JobQueryService(jobRepository, jobCachePort);
    }
}
