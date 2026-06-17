package com.altrix.job.infrastructure.config;

import com.altrix.job.domain.port.out.JobCachePort;
import com.altrix.job.domain.port.out.JobEventPublisherPort;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import com.altrix.job.domain.service.JobCommandService;
import com.altrix.job.domain.service.JobQueryService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    public JobCommandService jobCommandService(
            JobRepositoryPort jobRepository,
            JobCachePort jobCachePort,
            JobEventPublisherPort jobEventPublisher
    ) {
        return new JobCommandService(jobRepository, jobCachePort, jobEventPublisher);
    }

    @Bean
    public JobQueryService jobQueryService(
            JobRepositoryPort jobRepository,
            JobCachePort jobCachePort
    ) {
        return new JobQueryService(jobRepository, jobCachePort);
    }

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
