package com.altrix.orchestrator.infrastructure.config;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.CodeIndexingPort;
import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort;
import com.altrix.orchestrator.domain.port.out.MigratedFileStoragePort;
import com.altrix.orchestrator.domain.port.out.MigrationPlanCachePort;
import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import com.altrix.orchestrator.domain.port.out.ProviderConfigRepositoryPort;
import com.altrix.orchestrator.domain.port.out.ProviderRefreshPort;
import com.altrix.orchestrator.domain.port.out.TokenUsagePort;
import com.altrix.orchestrator.domain.port.out.WorkflowExecutionPort;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import com.altrix.orchestrator.domain.service.DocumentationIngestionService;
import com.altrix.orchestrator.domain.service.OrchestratorService;
import com.altrix.orchestrator.domain.service.ProviderConfigService;
import com.altrix.orchestrator.domain.service.SessionManagementService;
import com.altrix.orchestrator.domain.service.TokenUsageService;
import com.altrix.orchestrator.infra.ai.provider.factory.ProviderFactory;
import com.altrix.orchestrator.infrastructure.workflow.MigrationWorkflowGraph;
import io.minio.MinioClient;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({AiProvidersConfig.class, AiRoutingConfig.class, AiPricingConfig.class,
        EncryptionConfig.class, McpConfig.class, ApprovalConfig.class})
public class BeanConfig {

    @Bean
    public MigrationWorkflowGraph migrationWorkflowGraph(
            @Qualifier("contextAnalyzerAgent")  MigrationAgent<ProjectContext,    AnalysisReport>    contextAnalyzer,
            @Qualifier("migrationPlannerAgent") MigrationAgent<AnalysisReport,    MigrationPlan>     planner,
            @Qualifier("typedCoreMigratorAgent") MigrationAgent<ApprovedPlan,     MigrationArtifact> migrator,
            @Qualifier("sandboxValidatorAgent") MigrationAgent<MigrationArtifact, ValidationReport>  validator,
            @Qualifier("reportGeneratorAgent")  MigrationAgent<WorkflowOutcome,   MigrationReport>   reporter,
            ProgressNotifierPort                progressNotifier,
            BaseCheckpointSaver                 checkpointSaver
    ) {
        return new MigrationWorkflowGraph(
                contextAnalyzer, planner, migrator, validator, reporter,
                progressNotifier, checkpointSaver);
    }

    @Bean
    public OrchestratorService orchestratorService(
            WorkflowExecutionPort     workflowExecution,
            JobStatusUpdatePort       jobStatusUpdatePort,
            MigratedFileStoragePort   migratedFileStoragePort,
            ProgressNotifierPort      progressNotifierPort,
            CodeIndexingPort          codeIndexingPort,
            MigrationPlanCachePort    migrationPlanCachePort,
            WorkflowSessionRepository workflowSessionRepository
    ) {
        return new OrchestratorService(
                workflowExecution,
                jobStatusUpdatePort,
                migratedFileStoragePort,
                progressNotifierPort,
                codeIndexingPort,
                migrationPlanCachePort,
                workflowSessionRepository
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
    public SessionManagementService sessionManagementService(
            WorkflowSessionRepository workflowSessionRepository
    ) {
        return new SessionManagementService(workflowSessionRepository);
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
