package com.altrix.orchestrator.infrastructure.config;

import com.altrix.orchestrator.domain.port.out.AgentPort;
import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.CodeIndexingPort;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationPlanCachePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.ProviderConfigRepositoryPort;
import com.altrix.orchestrator.domain.port.out.ProviderRefreshPort;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;
import com.altrix.orchestrator.domain.service.DocumentationIngestionService;
import com.altrix.orchestrator.domain.service.OrchestratorService;
import com.altrix.orchestrator.domain.service.ProviderConfigService;
import com.altrix.orchestrator.domain.service.TokenUsageService;
import com.altrix.orchestrator.infrastructure.config.AiPricingConfig;
import com.altrix.orchestrator.infra.ai.provider.factory.ProviderFactory;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

@Configuration
@EnableAsync
@EnableConfigurationProperties({AiProvidersConfig.class, AiRoutingConfig.class, AiPricingConfig.class, EncryptionConfig.class, McpConfig.class})
public class BeanConfig {

    @Bean
    public OrchestratorService orchestratorService(
            List<AgentPort>         agents,
            JobStatusUpdatePort     jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort    progressNotifierPort,
            CodeIndexingPort        codeIndexingPort,
            MigrationPlanCachePort  migrationPlanCachePort,
            @Value("${ai.inter-agent-delay-ms:0}") long interAgentDelayMs
    ) {
        return new OrchestratorService(
                agents,
                jobStatusUpdatePort,
                migratedFileStoragePort,
                progressNotifierPort,
                codeIndexingPort,
                migrationPlanCachePort,
                interAgentDelayMs
        );
    }

    @Bean
    public DocumentationIngestionService documentationIngestionService(
            EmbeddingStorePort embeddingStore,
            DocumentationFetchPort docFetch
    ) {
        return new DocumentationIngestionService(embeddingStore, docFetch);
    }

    @Bean
    public TokenUsageService tokenUsageService(TokenUsagePort tokenUsagePort,
                                               AiPricingConfig pricingConfig) {
        return new TokenUsageService(tokenUsagePort, pricingConfig); // AiPricingConfig implements TokenPricingPort
    }

    @Bean
    public ProviderConfigService providerConfigService(
            ProviderConfigRepositoryPort configRepository,
            ApiKeyEncryptionPort     encryption,
            ProviderRefreshPort      providerRefresh,
            List<ProviderFactory>    factories
    ) {
        return new ProviderConfigService(configRepository, encryption, providerRefresh, factories);
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
