package com.migrator.job.infrastructure.config;

import com.migrator.job.domain.port.out.JobCachePort;
import com.migrator.job.domain.port.out.JobEventPublisher;
import com.migrator.job.domain.port.out.JobRepository;
import com.migrator.job.domain.service.JobCommandService;
import com.migrator.job.domain.service.JobQueryService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    public JobCommandService jobCommandService(
            JobRepository     jobRepository,
            JobCachePort      jobCachePort,
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

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}")   String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
