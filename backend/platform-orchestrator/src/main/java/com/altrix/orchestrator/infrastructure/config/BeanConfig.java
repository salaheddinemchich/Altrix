package com.altrix.orchestrator.infrastructure.config;

import com.altrix.common.domain.model.*;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.orchestrator.domain.port.in.ResumeMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.*;
import com.altrix.orchestrator.domain.service.*;
import com.altrix.orchestrator.infra.ai.provider.factory.ProviderFactory;
import com.altrix.orchestrator.infrastructure.ai.RetryContextBuilder;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({AiProvidersConfig.class, AiRoutingConfig.class, AiPricingConfig.class,
        EncryptionConfig.class, McpConfig.class, ApprovalConfig.class, AutoPauseConfig.class,
        ApprovalNotificationConfig.class, JwtConfig.class, RateLimitConfig.class, CacheConfig.class})
public class BeanConfig {

    @Bean
    public MigrationWorkflowGraph migrationWorkflowGraph(
            @Qualifier("contextAnalyzerAgent") MigrationAgent<ProjectContext, AnalysisReport> contextAnalyzer,
            @Qualifier("migrationPlannerAgent") MigrationAgent<AnalysisReport, MigrationPlan> planner,
            @Qualifier("typedCoreMigratorAgent") MigrationAgent<ApprovedPlan, MigrationArtifact> migrator,
            @Qualifier("sandboxValidatorAgent") MigrationAgent<MigrationArtifact, ValidationReport> validator,
            @Qualifier("reportGeneratorAgent") MigrationAgent<WorkflowOutcome, MigrationReport> reporter,
            ProgressNotifierPort progressNotifier,
            BaseCheckpointSaver checkpointSaver,
            RetryContextBuilder retryContextBuilder,
            @Value("${workflow.require-approval.enabled:true}") boolean requireApproval
    ) {
        return new MigrationWorkflowGraph(
                contextAnalyzer, planner, migrator, validator, reporter,
                progressNotifier, checkpointSaver, retryContextBuilder, requireApproval);
    }

    @Bean
    public OrchestratorService orchestratorService(
            WorkflowExecutionPort workflowExecution,
            JobStatusUpdatePort jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort progressNotifierPort,
            CodeIndexingPort codeIndexingPort,
            MigrationPlanCachePort migrationPlanCachePort,
            WorkflowSessionRepository workflowSessionRepository,
            AutoPauseConfig autoPauseConfig
    ) {
        return new OrchestratorService(
                workflowExecution,
                jobStatusUpdatePort,
                migratedFileStoragePort,
                progressNotifierPort,
                codeIndexingPort,
                migrationPlanCachePort,
                workflowSessionRepository,
                autoPauseConfig.threshold()
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
            ApiKeyEncryptionPort encryption,
            ProviderRefreshPort providerRefresh,
            List<ProviderFactory> factories
    ) {
        return new ProviderConfigService(configRepository, encryption, providerRefresh, factories);
    }

    @Bean
    public SessionManagementService sessionManagementService(
            WorkflowSessionRepository workflowSessionRepository,
            ResumeMigrationUseCase resumeMigration,
            @Qualifier("approvalResumeExecutor") Executor resumeExecutor
    ) {
        return new SessionManagementService(workflowSessionRepository, resumeMigration, resumeExecutor);
    }

    /**
     * Pool that runs the migrator → validator → reporter continuation after a
     * reviewer approves the plan (#10).  Sized small intentionally: each task
     * holds a heavyweight AI call, so we bound concurrency to avoid hammering
     * provider rate limits.  Bounded queue + caller-runs policy ensures
     * back-pressure surfaces to the approve REST call rather than silently
     * dropping work.
     */
    @Bean("approvalResumeExecutor")
    public Executor approvalResumeExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("approval-resume-");
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    @Bean
    public ResumeMigrationUseCase resumeMigrationService(
            WorkflowSessionRepository workflowSessionRepository,
            @Qualifier("typedCoreMigratorAgent") MigrationAgent<ApprovedPlan, MigrationArtifact> migrator,
            @Qualifier("sandboxValidatorAgent") MigrationAgent<MigrationArtifact, ValidationReport> validator,
            @Qualifier("reportGeneratorAgent") MigrationAgent<WorkflowOutcome, MigrationReport> reporter,
            MigratedFileStoragePort migratedFileStoragePort,
            JobStatusUpdatePort jobStatusUpdatePort,
            ProgressNotifierPort progressNotifierPort
    ) {
        return new ResumeMigrationService(
                workflowSessionRepository,
                migrator, validator, reporter,
                migratedFileStoragePort, jobStatusUpdatePort, progressNotifierPort);
    }

    @Bean
    public PlanSimilarityService planSimilarityService(
            PlanSimilarityCachePort planSimilarityCache,
            @Value("${ai.plan-similarity.threshold:0.85}") double threshold
    ) {
        return new PlanSimilarityService(planSimilarityCache, threshold);
    }

    @Bean
    public TokenService tokenService(
            RefreshTokenRepository refreshTokenRepository,
            TokenBlacklistPort tokenBlacklistPort
    ) {
        return new TokenService(refreshTokenRepository, tokenBlacklistPort);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
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
