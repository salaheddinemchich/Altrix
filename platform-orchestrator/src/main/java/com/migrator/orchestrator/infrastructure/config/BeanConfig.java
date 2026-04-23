package com.migrator.orchestrator.infrastructure.config;

import com.migrator.orchestrator.domain.port.out.AgentPort;
import com.migrator.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.migrator.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.migrator.orchestrator.domain.port.out.ProgressNotifierPort;
import com.migrator.orchestrator.domain.service.OrchestratorService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires domain services to adapter implementations.
 * Spring auto-discovers all AgentPort beans and injects them as a list.
 */
@Configuration
public class BeanConfig {

    @Bean
    public OrchestratorService orchestratorService(
            List<AgentPort>         agents,
            JobStatusUpdatePort     jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort    progressNotifierPort
    ) {
        return new OrchestratorService(
                agents,
                jobStatusUpdatePort,
                migratedFileStoragePort,
                progressNotifierPort
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
