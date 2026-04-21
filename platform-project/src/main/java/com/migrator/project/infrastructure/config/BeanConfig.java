package com.migrator.project.infrastructure.config;

import com.migrator.project.domain.port.out.FileStoragePort;
import com.migrator.project.domain.port.out.ProjectEventPublisher;
import com.migrator.project.domain.port.out.ProjectRepository;
import com.migrator.project.domain.service.BuildSystemDetector;
import com.migrator.project.domain.service.ProjectService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires domain services to their adapter implementations.
 *
 * <p>This is the only place in the codebase where domain services are
 * instantiated with {@code new}. Spring injects the adapter beans
 * (which implement the port interfaces) automatically.
 *
 * <p>This keeps the domain services free of {@code @Service} annotations —
 * they remain pure Java, testable without Spring context.
 */
@Configuration
public class BeanConfig {

    @Bean
    public BuildSystemDetector buildSystemDetector() {
        return new BuildSystemDetector();
    }

    @Bean
    public ProjectService projectService(
            ProjectRepository     projectRepository,
            FileStoragePort       fileStoragePort,
            ProjectEventPublisher eventPublisher,
            BuildSystemDetector   buildSystemDetector
    ) {
        return new ProjectService(
                projectRepository,
                fileStoragePort,
                eventPublisher,
                buildSystemDetector
        );
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
