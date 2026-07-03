package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.domain.model.MigrationReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.model.WorkflowOutcome;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.ContextAnalysisCachePort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.domain.port.out.FileMigrationCachePort;
import com.altrix.orchestrator.domain.port.out.FileProvenanceRepository;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.MigrationDecisionRegistryPort;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintPort;
import com.altrix.orchestrator.domain.service.PlanSimilarityService;
import com.altrix.orchestrator.infrastructure.ai.ContextPruner;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.config.MigrationConfig;
import com.altrix.orchestrator.infrastructure.contract.ContractRepairer;
import com.altrix.orchestrator.infrastructure.contract.ContractValidator;
import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Category;
import com.altrix.orchestrator.infrastructure.hybrid.CdiLookupCallScanner;
import com.altrix.orchestrator.infrastructure.hybrid.CdiStatelessConverter;
import com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerConversionDetector;
import com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer;
import com.altrix.orchestrator.infrastructure.hybrid.HybridPublishRewriter;
import com.altrix.orchestrator.infrastructure.hybrid.HybridScaffoldingGenerator;
import com.altrix.orchestrator.infrastructure.hybrid.PubSubConfigAnchor;
import com.altrix.orchestrator.infrastructure.hybrid.PubSubWrapperRemover;
import com.altrix.orchestrator.infrastructure.hybrid.SpringKafkaTargetConformanceDetector;
import com.altrix.orchestrator.infrastructure.hybrid.TopicBootstrapAnchor;
import com.altrix.orchestrator.infrastructure.hybrid.SpringKafkaTxGapDetector;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakRepairer;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator;
import com.altrix.orchestrator.infrastructure.migration.MigrationClusterPlanner;
import com.altrix.orchestrator.infrastructure.migration.PomDependencyReconciler;
import com.altrix.orchestrator.infrastructure.migration.PomSanitizer;
import com.altrix.orchestrator.infrastructure.migration.ProjectSymbolValidator;
import com.altrix.orchestrator.infrastructure.report.DependencyDiffAnalyzer;
import com.altrix.orchestrator.infrastructure.report.MigrationReportBuilder;
import com.altrix.orchestrator.infrastructure.semantic.DependencyValidator;
import com.altrix.orchestrator.infrastructure.semantic.DeterministicRepairEngine;
import com.altrix.orchestrator.infrastructure.semantic.JavaxToJakartaRewriter;
import com.altrix.orchestrator.infrastructure.semantic.LombokConstructorReconciler;
import com.altrix.orchestrator.infrastructure.semantic.MessagingConfigRepairer;
import com.altrix.orchestrator.infrastructure.semantic.SpringKafkaOverEngineeringDetector;
import com.altrix.orchestrator.infrastructure.semantic.SpringValueConstructorInjectionFixer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wired fixture: chains real instances of every Agent
 * (ContextAnalyzer → MigrationPlanner → CoreMigrator → SemanticValidator →
 * ReportGenerator) with only {@link AiPort} and the heavy infrastructure
 * ports mocked — mirrors the mocking convention already established by
 * {@code CoreMigratorAgentTest} / {@code SemanticValidatorAgentTest} /
 * {@code ReportGeneratorAgentTest} rather than introducing a new style.
 * Proves the {@code jakartaMessagingTarget} flows end-to-end and that the
 * default (native kafka-clients) path produces none of the hybrid-specific
 * output.
 */
class JakartaSpringKafkaHybridPipelineTest {

    private static final String POM = "<project><dependencies><dependency>"
            + "<groupId>jakarta.platform</groupId><artifactId>jakarta.jakartaee-api</artifactId>"
            + "</dependency></dependencies></project>";

    private static final String LISTENER_SOURCE =
            "package p;\nimport google.cloud.pubsub.*;\npublic class OrderListener {}";

    private static final String STORE_SOURCE = """
            package p;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class OrderStore {
                public void markPaid(String id) { }
            }""";

    private static final String MIGRATED_LISTENER = """
            package p;
            import org.springframework.kafka.annotation.KafkaListener;
            import org.springframework.stereotype.Component;
            import com.example.config.CdiLookup;
            @Component
            public class OrderListener {
                @KafkaListener(topics = "orders")
                public void handle(String m) {
                    CdiLookup.get(OrderStore.class).markPaid("x");
                }
            }""";

    private static final String EJB_CONVERSION_NOTE = "Converted to @Stateless EJB — required for transaction "
            + "correctness when invoked from Spring Kafka consumer threads.";

    /** Exposes the intermediate artifacts the assertions need, not just the final report. */
    private record PipelineResult(MigrationReport report, MigrationArtifact migrated) {
    }

    private PipelineResult runPipeline(JakartaMessagingTarget target) {
        AiPort aiPort = mock(AiPort.class);
        FileReaderPort fileReader = mock(FileReaderPort.class);
        Map<String, String> sourceFiles = Map.of(
                "pom.xml", POM, "OrderListener.java", LISTENER_SOURCE, "OrderStore.java", STORE_SOURCE);
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(sourceFiles);

        // ── Stage 1 — Context Analyzer ────────────────────────────────────
        ContextAnalysisCachePort analysisCache = mock(ContextAnalysisCachePort.class);
        when(analysisCache.get(anyString())).thenReturn(Optional.empty());
        ContextAnalyzerAgent contextAnalyzer = new ContextAnalyzerAgent(aiPort, fileReader, analysisCache);
        when(aiPort.chatFast(anyString(), anyString())).thenReturn(
                "{\"detectedComponents\":[\"p.OrderListener\"],"
                        + "\"detectedIntegrations\":[\"Google Pub/Sub\"],\"summary\":\"PubSub order project\"}");
        ProjectContext ctx = ProjectContext.builder()
                .jobId("job-1").projectId("p1").storageKey("uploads/p1.zip")
                .jakartaMessagingTarget(target)
                .build();
        AnalysisReport analysis = contextAnalyzer.execute(ctx);

        // ── Stage 2 — Migration Planner ───────────────────────────────────
        PlanSimilarityService planSimilarityService = mock(PlanSimilarityService.class);
        when(planSimilarityService.findSimilar(any())).thenReturn(Optional.empty());
        MigrationPlannerAgent planner = new MigrationPlannerAgent(aiPort, planSimilarityService, fileReader);
        when(aiPort.chatFast(anyString(), anyString())).thenReturn("""
                {"targetStack":"Spring Boot 3 + Apache Kafka","steps":["Step 1: replace PubSub"],\
                "riskLevel":"MEDIUM","estimatedEffort":"3 days","summary":"migrate p1"}""");
        MigrationPlan plan = planner.execute(analysis);

        // ── Stage 3 — Core Migrator ───────────────────────────────────────
        ContextPruner contextPruner = mock(ContextPruner.class);
        when(contextPruner.prune(any(), any())).thenAnswer(inv -> {
            Map<String, String> all = inv.getArgument(0);
            Map<String, String> pruned = new LinkedHashMap<>(all);
            pruned.remove("pom.xml");
            return new PrunedContext(pruned, all.size(), 1);
        });
        PomDependencyReconciler pomDependencyReconciler = mock(PomDependencyReconciler.class);
        when(pomDependencyReconciler.reconcile(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiPort.chat(anyString(), anyString())).thenReturn(MIGRATED_LISTENER);

        CoreMigratorAgent migrator = new CoreMigratorAgent(
                new CdiStatelessConverter(new CdiLookupCallScanner()), new HybridScaffoldingGenerator(),
                new HybridConsumerTransformer(),
                new TopicBootstrapAnchor(), new PubSubConfigAnchor(),
                new HybridPublishRewriter(),
                new PubSubWrapperRemover(),
                aiPort, fileReader, contextPruner,
                mock(FileMigrationCachePort.class), mock(EmbeddingStorePort.class),
                mock(FileProvenanceRepository.class), mock(PomSanitizer.class),
                mock(ProjectSymbolValidator.class), mock(MigrationConfig.class),
                new ContractValidator(), mock(ContractRepairer.class),
                new PubSubLeakValidator(), mock(PubSubLeakRepairer.class),
                mock(ProjectBlueprintPort.class), mock(MigrationClusterPlanner.class),
                pomDependencyReconciler);

        MigrationArtifact migrated = migrator.execute(ApprovedPlan.autoApproved(plan));

        // ── Stage 4 — Semantic Validator ──────────────────────────────────
        KafkaMigrationKnowledgeBase kb = new KafkaMigrationKnowledgeBase(List.of(), List.of(), List.of(), List.of());
        SemanticValidatorAgent semanticValidator = new SemanticValidatorAgent(
                new ContractValidator(), new PubSubLeakValidator(), kb,
                new DeterministicRepairEngine(kb), new DependencyValidator(kb),
                new JavaxToJakartaRewriter(), new LombokConstructorReconciler(),
                new SpringValueConstructorInjectionFixer(), new MessagingConfigRepairer(),
                new SpringKafkaOverEngineeringDetector(), new SpringKafkaTxGapDetector(new CdiLookupCallScanner()),
                new SpringKafkaTargetConformanceDetector(), new HybridConsumerConversionDetector());

        MigrationArtifact validated = semanticValidator.execute(migrated);

        // ── Stage 5 — Report Generator ────────────────────────────────────
        ReportGeneratorAgent reportGenerator = new ReportGeneratorAgent(
                new MigrationReportBuilder(), new DependencyDiffAnalyzer(),
                mock(ProjectBlueprintPort.class), mock(MigrationDecisionRegistryPort.class),
                mock(FileProvenanceRepository.class), fileReader);

        WorkflowOutcome outcome = new WorkflowOutcome("p1", analysis, plan, validated, ValidationReport.pending("p1"));
        return new PipelineResult(reportGenerator.execute(outcome), migrated);
    }

    @Test
    void hybridTarget_endToEnd_reportDocumentsHybridArchitectureAndEjbConversion() {
        MigrationReport report = runPipeline(JakartaMessagingTarget.SPRING_KAFKA_HYBRID).report();

        assertThat(report.content()).contains("Spring Kafka hybrid (manual ApplicationContext)");
        assertThat(report.content()).contains(EJB_CONVERSION_NOTE);
    }

    @Test
    void nativeKafkaClientsTarget_endToEnd_producesNoHybridArtifacts() {
        MigrationReport report = runPipeline(JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS).report();

        assertThat(report.content()).doesNotContain("Spring Kafka hybrid");
        assertThat(report.content()).doesNotContain("Converted to @Stateless EJB");
    }

    // ── Regression for job 2c91a1fc root cause 1 ──────────────────────────────
    // A genuine Pub/Sub project has NO bridge files to map from. Before the
    // HybridScaffoldingGenerator, the migrated @KafkaListener referenced
    // CdiLookup which the 1:1 migrator could never create → "cannot find
    // symbol: CdiLookup" at compile. The generator must ADD all 5 bridge files.

    @Test
    void hybridTarget_generatesAllFiveBridgeFiles_whenNonePreexist() {
        MigrationArtifact migrated = runPipeline(JakartaMessagingTarget.SPRING_KAFKA_HYBRID).migrated();

        java.util.Set<String> generatedClasses = migrated.files().stream()
                .filter(f -> f.changeType() == FileChangeType.CREATED)
                .map(f -> fileName(f.newPath()))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(generatedClasses).contains(
                "SpringKafkaConfig.java", "SpringContextBootstrapper.java",
                "AppStartupListener.java", "SpringBeanBridge.java", "CdiLookup.java");

        // The original consumer survived as a Spring @KafkaListener reaching CDI via CdiLookup.
        String listener = migrated.files().stream()
                .filter(f -> "OrderListener.java".equals(f.newPath()))
                .map(MigratedFile::content).findFirst().orElse("");
        assertThat(listener).contains("@KafkaListener").contains("CdiLookup.get");
    }

    @Test
    void nativeKafkaClientsTarget_generatesNoBridgeFiles() {
        MigrationArtifact migrated = runPipeline(JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS).migrated();

        boolean anyGenerated = migrated.files().stream()
                .anyMatch(f -> f.changeType() == FileChangeType.CREATED
                        && fileName(f.newPath()).equals("CdiLookup.java"));
        assertThat(anyGenerated).isFalse();
    }

    // ── Regression for job 2c91a1fc root cause 2 (target-pattern drift) ───────
    // One file migrated toward raw kafka-clients (new KafkaProducer +
    // producerProperties()) instead of the hybrid KafkaTemplate idiom. The real
    // SemanticValidatorAgent wiring must produce a SPRING_KAFKA_TARGET_DRIFT
    // finding, and must NOT produce one on the NATIVE_KAFKA_CLIENTS target.

    private static final String DRIFTED_SERVICE = """
            package p;
            import org.apache.kafka.clients.producer.KafkaProducer;
            import java.util.Properties;
            public class PubSubService {
                private KafkaProducer<String, String> producer;
                void init() {
                    Properties props = pubSubConfig.producerProperties();
                    producer = new KafkaProducer<>(props);
                }
            }""";

    @Test
    void hybridTarget_flagsTargetDrift_whenFileUsesRawKafkaClients() {
        SemanticValidatorAgent agent = hybridSemanticValidator();
        SemanticValidationReport report = agent.validate("p1",
                Map.of("p/PubSubService.java", DRIFTED_SERVICE), null,
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);

        assertThat(report.countOf(Category.SPRING_KAFKA_TARGET_DRIFT)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void nativeKafkaClientsTarget_doesNotFlagTargetDrift() {
        SemanticValidatorAgent agent = hybridSemanticValidator();
        SemanticValidationReport report = agent.validate("p1",
                Map.of("p/PubSubService.java", DRIFTED_SERVICE), null,
                JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);

        assertThat(report.countOf(Category.SPRING_KAFKA_TARGET_DRIFT)).isZero();
    }

    private SemanticValidatorAgent hybridSemanticValidator() {
        KafkaMigrationKnowledgeBase kb = new KafkaMigrationKnowledgeBase(List.of(), List.of(), List.of(), List.of());
        return new SemanticValidatorAgent(
                new ContractValidator(), new PubSubLeakValidator(), kb,
                new DeterministicRepairEngine(kb), new DependencyValidator(kb),
                new JavaxToJakartaRewriter(), new LombokConstructorReconciler(),
                new SpringValueConstructorInjectionFixer(), new MessagingConfigRepairer(),
                new SpringKafkaOverEngineeringDetector(), new SpringKafkaTxGapDetector(new CdiLookupCallScanner()),
                new SpringKafkaTargetConformanceDetector(), new HybridConsumerConversionDetector());
    }

    private static String fileName(String path) {
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }
}
