package com.altrix.orchestrator.infrastructure.config;

import com.altrix.common.domain.model.*;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.orchestrator.domain.port.in.ResumeMigrationUseCase;
import com.altrix.orchestrator.domain.port.out.*;
import com.altrix.orchestrator.domain.service.*;
import com.altrix.orchestrator.infrastructure.ai.RetryContextBuilder;
import com.altrix.orchestrator.infrastructure.ai.provider.factory.ProviderFactory;
import com.altrix.orchestrator.infrastructure.workflow.MigrationWorkflowGraph;
import io.minio.MinioClient;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
        ApprovalNotificationConfig.class, JwtConfig.class, RateLimitConfig.class, CacheConfig.class,
        SandboxDockerConfig.class, MigrationConfig.class, MigrationApplyConfig.class,
        DocumentationCorpusConfig.class, KafkaMigrationKnowledgeBase.class})
public class BeanConfig {

    @Bean
    public MigrationWorkflowGraph migrationWorkflowGraph(
            @Qualifier("contextAnalyzerAgent") MigrationAgent<ProjectContext, AnalysisReport> contextAnalyzer,
            @Qualifier("migrationPlannerAgent") MigrationAgent<AnalysisReport, MigrationPlan> planner,
            @Qualifier("typedCoreMigratorAgent") MigrationAgent<ApprovedPlan, MigrationArtifact> migrator,
            @Qualifier("semanticValidatorAgent") MigrationAgent<MigrationArtifact, MigrationArtifact> semanticValidator,
            @Qualifier("sandboxValidatorAgent") MigrationAgent<MigrationArtifact, ValidationReport> validator,
            @Qualifier("reportGeneratorAgent") MigrationAgent<WorkflowOutcome, MigrationReport> reporter,
            ProgressNotifierPort progressNotifier,
            BaseCheckpointSaver checkpointSaver,
            RetryContextBuilder retryContextBuilder,
            @Value("${workflow.require-approval.enabled:true}") boolean requireApproval
    ) {
        return new MigrationWorkflowGraph(
                contextAnalyzer, planner, migrator, semanticValidator, validator, reporter,
                progressNotifier, checkpointSaver, retryContextBuilder, requireApproval);
    }

    @Bean
    public OrchestratorService orchestratorService(
            WorkflowExecutionPort workflowExecution,
            JobStatusUpdatePort jobStatusUpdatePort,
            MigratedFileStoragePort migratedFileStoragePort,
            ProgressNotifierPort progressNotifierPort,
            MigrationPlanCachePort migrationPlanCachePort,
            WorkflowSessionRepository workflowSessionRepository,
            AutoPauseConfig autoPauseConfig,
            @Qualifier("projectMapperAgent")
            MigrationAgent<ProjectContext, com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint> projectMapper
    ) {
        return new OrchestratorService(
                workflowExecution,
                jobStatusUpdatePort,
                migratedFileStoragePort,
                progressNotifierPort,
                migrationPlanCachePort,
                workflowSessionRepository,
                autoPauseConfig.threshold(),
                projectMapper
        );
    }

    @Bean
    public DocumentationIngestionService documentationIngestionService(
            EmbeddingStorePort embeddingStore,
            DocumentationFetchPort docFetch,
            com.altrix.orchestrator.infrastructure.rag.DomainAllowListValidator allowList,
            DocumentationCorpusConfig corpus
    ) {
        return new DocumentationIngestionService(embeddingStore, docFetch, allowList, corpus);
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
            @Qualifier("approvalResumeExecutor") Executor resumeExecutor,
            com.altrix.orchestrator.domain.port.out.JobStatusUpdatePort jobStatusUpdatePort,
            com.altrix.orchestrator.domain.port.out.ProgressNotifierPort progressNotifier
    ) {
        return new SessionManagementService(workflowSessionRepository, resumeMigration,
                resumeExecutor, jobStatusUpdatePort, progressNotifier);
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
            @Qualifier("semanticValidatorAgent") MigrationAgent<MigrationArtifact, MigrationArtifact> semanticValidator,
            @Qualifier("sandboxValidatorAgent") MigrationAgent<MigrationArtifact, ValidationReport> validator,
            @Qualifier("reportGeneratorAgent") MigrationAgent<WorkflowOutcome, MigrationReport> reporter,
            MigratedFileStoragePort migratedFileStoragePort,
            JobStatusUpdatePort jobStatusUpdatePort,
            ProgressNotifierPort progressNotifierPort,
            com.altrix.orchestrator.domain.port.out.MigrationReportRepository migrationReportRepository,
            // #98 — extra migrator retries when the validator returns failures.
            // Service-side clamps to [0, 5] so a misconfig can't cost 100 LLM
            // calls.  0 restores the pre-#98 single-attempt behaviour.
            @Value("${migration.validation.max-retries:1}") int maxValidationRetries
    ) {
        return new ResumeMigrationService(
                workflowSessionRepository,
                migrator, semanticValidator, validator, reporter,
                migratedFileStoragePort, jobStatusUpdatePort, progressNotifierPort,
                migrationReportRepository,
                maxValidationRetries);
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

    /**
     * The default {@link RestTemplate} request factory
     * ({@code SimpleClientHttpRequestFactory}, backed by {@code
     * HttpURLConnection}) cannot send PATCH requests — the JDK's
     * {@code HttpURLConnection} only allows a fixed legacy method set
     * (GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE) and throws {@code
     * ProtocolException: Invalid HTTP method: PATCH}.  GitHub's "update a
     * reference" endpoint (used to move a branch ref onto a new commit
     * after committing files — see {@code GitHubApiClient}) requires
     * PATCH, so every branch-strategy apply failed with that error.
     * {@link JdkClientHttpRequestFactory} is backed by {@code
     * java.net.http.HttpClient} (Java 11+), which supports PATCH natively.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(new JdkClientHttpRequestFactory());
    }

    // ── Migration Approval & Branch Strategy workflow (#PR-feature) ──────────

    @Bean
    public com.altrix.orchestrator.domain.service.RepositoryAccessService repositoryAccessService(
            com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort projects,
            com.altrix.orchestrator.domain.port.out.OAuthTokenLookupPort tokens,
            com.altrix.orchestrator.domain.port.out.RepositoryProviderPort provider,
            com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort applyState
    ) {
        return new com.altrix.orchestrator.domain.service.RepositoryAccessService(
                projects, tokens, provider, applyState);
    }

    @Bean
    public com.altrix.orchestrator.domain.service.BranchStrategyService branchStrategyService(
            com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase accessUseCase,
            com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort applyState,
            com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort auditLog,
            MigrationApplyConfig config
    ) {
        return new com.altrix.orchestrator.domain.service.BranchStrategyService(
                accessUseCase, applyState, auditLog, config);
    }

    @Bean
    public com.altrix.orchestrator.domain.service.MigrationApplyService migrationApplyService(
            com.altrix.orchestrator.domain.port.out.MigrationApplyStatePort applyState,
            com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort auditLog,
            com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort projects,
            com.altrix.orchestrator.domain.port.out.OAuthTokenLookupPort tokens,
            com.altrix.orchestrator.domain.port.in.CheckRepositoryAccessUseCase accessUseCase,
            com.altrix.orchestrator.infrastructure.apply.BranchStrategyHandlerRegistry handlers
    ) {
        return new com.altrix.orchestrator.domain.service.MigrationApplyService(
                applyState, auditLog, projects, tokens, accessUseCase, handlers);
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
