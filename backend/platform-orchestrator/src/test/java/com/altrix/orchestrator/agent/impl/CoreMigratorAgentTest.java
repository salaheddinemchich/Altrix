package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.enums.JakartaMessagingTarget;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileMigrationCachePort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.infrastructure.ai.ContextPruner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.altrix.common.domain.model.MigratedFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoreMigratorAgentTest {

    @Mock
    AiPort aiPort;
    @Mock
    FileReaderPort fileReader;
    @Mock
    ContextPruner contextPruner;
    @Mock
    FileMigrationCachePort migrationCache;
    @Mock
    com.altrix.orchestrator.domain.port.out.EmbeddingStorePort embeddingStore;
    @Mock
    com.altrix.orchestrator.domain.port.out.FileProvenanceRepository fileProvenanceRepository;
    @Mock
    com.altrix.orchestrator.infrastructure.migration.PomSanitizer pomSanitizer;
    @Mock
    com.altrix.orchestrator.infrastructure.migration.ProjectSymbolValidator projectSymbolValidator;
    @Mock
    com.altrix.orchestrator.infrastructure.config.MigrationConfig migrationConfig;
    @Mock
    com.altrix.orchestrator.infrastructure.contract.ContractValidator contractValidator;
    @Mock
    com.altrix.orchestrator.infrastructure.contract.ContractRepairer contractRepairer;
    @Mock
    com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator pubSubLeakValidator;
    @Mock
    com.altrix.orchestrator.infrastructure.leak.PubSubLeakRepairer pubSubLeakRepairer;
    @Mock
    com.altrix.orchestrator.domain.port.out.ProjectBlueprintPort projectBlueprintPort;
    @Mock
    com.altrix.orchestrator.infrastructure.migration.MigrationClusterPlanner clusterPlanner;
    @Mock
    com.altrix.orchestrator.infrastructure.migration.PomDependencyReconciler pomDependencyReconciler;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.CdiStatelessConverter cdiStatelessConverter;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.HybridScaffoldingGenerator hybridScaffoldingGenerator;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer hybridConsumerTransformer;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.TopicBootstrapAnchor topicBootstrapAnchor;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.PubSubConfigAnchor pubSubConfigAnchor;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.HybridPublishRewriter hybridPublishRewriter;
    @Mock
    com.altrix.orchestrator.infrastructure.hybrid.PubSubWrapperRemover pubSubWrapperRemover;
    @InjectMocks
    CoreMigratorAgent agent;

    /** Hybrid tests need the source-in transformer to return "nothing converted". */
    private void consumerTransformerNoop() {
        when(hybridConsumerTransformer.transform(any()))
                .thenReturn(new com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer.Result(
                        java.util.List.of(), java.util.List.of()));
    }

    /** Convenience: stub pruner to pass all files through unchanged. */
    private void prunerPassThrough(Map<String, String> files) {
        when(contextPruner.prune(any(), any()))
                .thenAnswer(inv -> new PrunedContext(files, files.size(), 0));
    }

    @Test
    void exposesNameAndOrder3() {
        assertThat(agent.getName()).isEqualTo("Core Migrator");
        assertThat(agent.getOrder()).isEqualTo(3);
    }

    @Test
    void execute_withStorageKey_migratesFilesWithPubSubCode() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "Spring Boot 3 + Kafka",
                List.of("Step 1"), "MEDIUM", "2 days", "migrate messaging", List.of(), null);
        ApprovedPlan approved = ApprovedPlan.autoApproved(plan);
        // Fixtures must be valid Java shape (package + type) — the migrator's
        // post-migration sanity check rejects content that has neither.
        Map<String, String> files = Map.of(
                "Listener.java",
                "package p;\nimport google.cloud.pubsub.*;\npublic class Listener {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(files);
        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("package p;\nimport org.apache.kafka.clients.*;\npublic class Listener {}");

        MigrationArtifact artifact = agent.execute(approved);

        assertThat(artifact.projectId()).isEqualTo("p1");
        assertThat(artifact.files()).hasSize(1);
        assertThat(artifact.files().get(0).changeType()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(artifact.summary()).contains("1/1");
    }

    @Test
    void execute_noStorageKey_returnsEmptyArtifact_withoutCallingAi() {
        ApprovedPlan approved = ApprovedPlan.autoApproved(MigrationPlan.empty("p1"));

        MigrationArtifact artifact = agent.execute(approved);

        assertThat(artifact.projectId()).isEqualTo("p1");
        assertThat(artifact.files()).isEmpty();
        assertThat(artifact.summary()).contains("storageKey missing");
        verifyNoInteractions(aiPort, fileReader, contextPruner);
    }

    @Test
    void execute_fileWithNoPubSubCode_passedThrough_unchanged() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(), null);
        Map<String, String> files = Map.of("Service.java", "import java.util.List; class Service {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(files);

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(artifact.files()).hasSize(1);
        assertThat(artifact.files().get(0).changeType()).isEqualTo(FileChangeType.UNCHANGED);
        verifyNoInteractions(aiPort);
    }

    @Test
    void execute_aiFails_keepsOriginalFile_andContinues() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(), null);
        Map<String, String> files = Map.of("Broken.java", "import google.cloud.pubsub; class Broken {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(files);
        when(aiPort.chat(anyString(), anyString())).thenThrow(new RuntimeException("AI timeout"));

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(artifact.files()).hasSize(1);
        assertThat(artifact.files().get(0).changeType()).isEqualTo(FileChangeType.UNCHANGED);
        assertThat(artifact.files().get(0).diffSummary()).contains("AI unavailable");
    }

    @Test
    void execute_contextPrunerExcludesFiles_excludedFilesAddedAsUnchanged() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "",
                List.of("Listener.java"), null);
        Map<String, String> allFiles = Map.of(
                "Listener.java",
                "package p;\nimport google.cloud.pubsub.*;\npublic class Listener {}",
                "Service.java",
                "package p;\npublic class Service {}"
        );
        Map<String, String> pruned = Map.of("Listener.java", allFiles.get("Listener.java"));

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(allFiles);
        when(contextPruner.prune(any(), any()))
                .thenReturn(new PrunedContext(pruned, 2, 1));
        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("package p;\nimport org.apache.kafka.clients.*;\npublic class Listener {}");

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(artifact.files()).hasSize(2);
        // Listener.java was migrated
        assertThat(artifact.files().stream()
                .filter(f -> f.originalPath().equals("Listener.java"))
                .findFirst().get().changeType()).isEqualTo(FileChangeType.MODIFIED);
        // Service.java was excluded by pruner — added as unchanged
        assertThat(artifact.files().stream()
                .filter(f -> f.originalPath().equals("Service.java"))
                .findFirst().get().diffSummary()).contains("pruner");
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }

    // ── Jakarta EE + Spring Kafka hybrid target selection ─────────────────

    private static Map<String, String> jakartaFilesWith(String listenerContent) {
        return Map.of(
                "pom.xml", "<project><dependencies><dependency>"
                        + "<groupId>jakarta.platform</groupId><artifactId>jakarta.jakartaee-api</artifactId>"
                        + "</dependency></dependencies></project>",
                "Listener.java", listenerContent);
    }

    /** pom.xml is present in these fixtures — stub the reconciler as a no-op pass-through. */
    private void pomReconcilerPassThrough() {
        when(pomDependencyReconciler.reconcile(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void execute_hybridTarget_selectsSpringKafkaHybridPrefix_neverInferredFromSource() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
        Map<String, String> files = jakartaFilesWith(
                "package p;\nimport google.cloud.pubsub.*;\npublic class Listener {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of("Listener.java", files.get("Listener.java")));
        pomReconcilerPassThrough();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        when(aiPort.chat(systemPrompt.capture(), anyString()))
                .thenReturn("package p;\nimport org.apache.kafka.clients.*;\npublic class Listener {}");
        when(cdiStatelessConverter.convert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridScaffoldingGenerator.generate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridPublishRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        consumerTransformerNoop();

        agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(systemPrompt.getValue()).contains("Jakarta EE 10 + Spring Kafka hybrid");
    }

    @Test
    void execute_nativeKafkaClientsTarget_stillSelectsDefaultJakartaPrefix_regressionGuard() {
        // Default target (NATIVE_KAFKA_CLIENTS via MigrationPlan.empty's default field, or
        // explicit) must keep selecting the unchanged, original Jakarta prefix.
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.NATIVE_KAFKA_CLIENTS);
        Map<String, String> files = jakartaFilesWith(
                "package p;\nimport google.cloud.pubsub.*;\npublic class Listener {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of("Listener.java", files.get("Listener.java")));
        pomReconcilerPassThrough();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        when(aiPort.chat(systemPrompt.capture(), anyString()))
                .thenReturn("package p;\nimport org.apache.kafka.clients.*;\npublic class Listener {}");

        agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(systemPrompt.getValue()).contains("Jakarta EE 10 (NO Spring on the classpath)");
        assertThat(systemPrompt.getValue()).doesNotContain("Spring Kafka hybrid");
        verifyNoInteractions(cdiStatelessConverter);
        // The scaffolding generator + consumer transform + anchors + publish rewriter are hybrid-only —
        // never touched on the native path.
        verifyNoInteractions(hybridScaffoldingGenerator);
        verifyNoInteractions(hybridConsumerTransformer);
        verifyNoInteractions(topicBootstrapAnchor);
        verifyNoInteractions(pubSubConfigAnchor);
        verifyNoInteractions(hybridPublishRewriter);
        verifyNoInteractions(pubSubWrapperRemover);
    }

    @Test
    void execute_hybridTarget_invokesCdiStatelessConverter() {
        MigrationPlan hybridPlan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
        Map<String, String> files = jakartaFilesWith("package p;\npublic class Listener {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of("Listener.java", files.get("Listener.java")));
        pomReconcilerPassThrough();
        // No Pub/Sub code in Listener.java — it's passed through unchanged, AI never called.
        // The hybrid-only CdiStatelessConverter pass runs unconditionally on the file set
        // regardless of whether AI touched any file, so it must still be invoked.
        when(cdiStatelessConverter.convert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridScaffoldingGenerator.generate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridPublishRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        consumerTransformerNoop();

        agent.execute(ApprovedPlan.autoApproved(hybridPlan));

        verify(cdiStatelessConverter).convert(any());
        verify(hybridScaffoldingGenerator).generate(any(), any());
        verify(hybridConsumerTransformer).transform(any());
        verify(topicBootstrapAnchor).analyze(any());
        verify(pubSubConfigAnchor).anchor(any());
        verifyNoInteractions(aiPort);
    }

    // ── retry-scope narrowing (regression: job d3fa6347 — the LLM re-rewrote
    //    healthy checkpoint files on retry and corrupted them) ───────────────

    private static MigratedFile modified(String path, String content) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.MODIFIED).diffSummary("Migrated Pub/Sub → Kafka")
                .build();
    }

    @Test
    void execute_retryWithFailingPaths_onlyImplicatedFileGoesToLlm_healthyKeptVerbatim() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "Spring Boot 3 + Kafka",
                List.of(), "MEDIUM", "", "", List.of(), null);
        // Both checkpoint contents still trip PubSubDetector (realistic: the real
        // pollers keep PubSubConfig references after migration) — without
        // narrowing BOTH would go back to the LLM.
        String healthyCheckpoint = "package p;\n// google.cloud.pubsub bridge\npublic class Healthy {}";
        String failingCheckpoint = "package p;\n// google.cloud.pubsub bridge\npublic class Failing {}";
        Map<String, String> source = Map.of(
                "src/Healthy.java", "package p;\nimport google.cloud.pubsub.*;\npublic class Healthy {}",
                "src/Failing.java", "package p;\nimport google.cloud.pubsub.*;\npublic class Failing {}");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(source);
        when(contextPruner.prune(any(), any()))
                .thenAnswer(inv -> new PrunedContext(inv.getArgument(0), 2, 0));

        MigrationArtifact checkpoint = new MigrationArtifact("p1", List.of(
                modified("src/Healthy.java", healthyCheckpoint),
                modified("src/Failing.java", failingCheckpoint)), "attempt 1", null);
        ApprovedPlan retry = ApprovedPlan.autoApproved(plan)
                .withRetryContext("fix Failing.java", checkpoint, List.of("src/Failing.java"));

        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("package p;\npublic class Failing { /* fixed */ }");

        MigrationArtifact artifact = agent.execute(retry);

        // Only the implicated file hit the LLM.
        verify(aiPort, times(1)).chat(anyString(), anyString());
        MigratedFile healthy = artifact.files().stream()
                .filter(f -> "src/Healthy.java".equals(f.newPath())).findFirst().orElseThrow();
        assertThat(healthy.content()).isEqualTo(healthyCheckpoint);
        assertThat(healthy.diffSummary()).contains("Retry checkpoint");
        MigratedFile failing = artifact.files().stream()
                .filter(f -> "src/Failing.java".equals(f.newPath())).findFirst().orElseThrow();
        assertThat(failing.content()).contains("/* fixed */");
    }

    @Test
    void execute_retryWithoutFailingPaths_noNarrowing_bothFilesRemigrated() {
        // No per-file findings (e.g. a boot timeout) → empty failingPaths →
        // previous behaviour: the whole pruned set goes back to the LLM.
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "Spring Boot 3 + Kafka",
                List.of(), "MEDIUM", "", "", List.of(), null);
        Map<String, String> source = Map.of(
                "src/A.java", "package p;\nimport google.cloud.pubsub.*;\npublic class A {}",
                "src/B.java", "package p;\nimport google.cloud.pubsub.*;\npublic class B {}");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(source);
        when(contextPruner.prune(any(), any()))
                .thenAnswer(inv -> new PrunedContext(inv.getArgument(0), 2, 0));

        MigrationArtifact checkpoint = new MigrationArtifact("p1", List.of(
                modified("src/A.java", "package p;\n// google.cloud.pubsub\npublic class A {}"),
                modified("src/B.java", "package p;\n// google.cloud.pubsub\npublic class B {}")),
                "attempt 1", null);
        ApprovedPlan retry = ApprovedPlan.autoApproved(plan)
                .withRetryContext("boot timed out", checkpoint, List.of());

        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("package p;\npublic class X { /* remigrated */ }");

        agent.execute(retry);

        verify(aiPort, times(2)).chat(anyString(), anyString());
    }

    // ── hybrid wrapper-glue deletion (regression: job d3fa6347 — the LLM
    //    re-implemented PubSubService with an Iterable-vs-List type error) ───

    @Test
    void execute_hybridTarget_deletesWrapperGlue_whenConsumersFullyConverted() {
        MigrationPlan hybridPlan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
        Map<String, String> files = Map.of(
                "pom.xml", "<project><dependencies><dependency>"
                        + "<groupId>jakarta.platform</groupId><artifactId>jakarta.jakartaee-api</artifactId>"
                        + "</dependency></dependencies></project>",
                "Wrapper.java", "package p;\npublic class Wrapper {}",
                "Listener.java", "package p;\npublic class Listener {}");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of(
                "Wrapper.java", files.get("Wrapper.java"),
                "Listener.java", files.get("Listener.java")));
        pomReconcilerPassThrough();
        when(cdiStatelessConverter.convert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridScaffoldingGenerator.generate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridPublishRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        consumerTransformerNoop(); // zero bails → full deterministic coverage → deletion armed
        when(pubSubWrapperRemover.detect(any())).thenReturn(java.util.Set.of("Wrapper.java"));

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(hybridPlan));

        assertThat(artifact.files())
                .noneMatch(f -> "Wrapper.java".equals(f.newPath()) || "Wrapper.java".equals(f.originalPath()));
        assertThat(artifact.files()).anyMatch(f -> "Listener.java".equals(f.newPath()));
        verifyNoInteractions(aiPort); // wrapper deleted, listener has no Pub/Sub code
    }

    // ── contract-repair protection (regression: job d7b6d473 — the repair LLM
    //    nulled the CdiLookup bridge call in both deterministic pollers) ──────

    /** The generated bridge classes must be in the artifact BEFORE contract
     *  validation runs — otherwise the pollers' CdiLookup references validate
     *  against an incomplete project and get "repaired". */
    @Test
    void execute_hybridTarget_scaffoldingGeneratedBeforeContractValidation() {
        MigrationPlan hybridPlan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
        Map<String, String> files = jakartaFilesWith("package p;\npublic class Listener {}");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of("Listener.java", files.get("Listener.java")));
        pomReconcilerPassThrough();
        when(cdiStatelessConverter.convert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridPublishRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        consumerTransformerNoop();
        // Generator ADDS the bridge file (realistic: 5 CREATED files).
        when(hybridScaffoldingGenerator.generate(any(), any())).thenAnswer(inv -> {
            List<MigratedFile> in = new java.util.ArrayList<>((List<MigratedFile>) inv.getArgument(0));
            in.add(MigratedFile.builder()
                    .originalPath("src/p/CdiLookup.java").newPath("src/p/CdiLookup.java")
                    .content("package p;\npublic final class CdiLookup {}")
                    .changeType(FileChangeType.CREATED).diffSummary("Generated bridge").build());
            return in;
        });
        ArgumentCaptor<Map<String, String>> validated = ArgumentCaptor.forClass(Map.class);
        when(contractValidator.validate(validated.capture())).thenReturn(List.of());

        agent.execute(ApprovedPlan.autoApproved(hybridPlan));

        assertThat(validated.getValue()).containsKey("src/p/CdiLookup.java");
    }

    /** Even when the repairer rewrites everything it was handed, deterministic
     *  and generated files must come back byte-identical; only LLM files may
     *  carry the "Contract-repair patch applied" stamp. */
    @Test
    void execute_hybridTarget_contractRepairNeverRewritesDeterministicOrGeneratedFiles() {
        MigrationPlan hybridPlan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
        String pollerSource = "package p;\nimport jakarta.ejb.Schedule;\npublic class Poller {}";
        String llmSource = "package p;\nimport google.cloud.pubsub.*;\npublic class Svc {}";
        Map<String, String> files = Map.of(
                "pom.xml", "<project><dependencies><dependency>"
                        + "<groupId>jakarta.platform</groupId><artifactId>jakarta.jakartaee-api</artifactId>"
                        + "</dependency></dependencies></project>",
                "src/p/Poller.java", pollerSource,
                "src/p/Svc.java", llmSource);
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of("src/p/Poller.java", pollerSource, "src/p/Svc.java", llmSource));
        pomReconcilerPassThrough();
        when(cdiStatelessConverter.convert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridPublishRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("package p;\npublic class Svc { /* llm */ }");

        // Deterministic transform converts the poller.
        String deterministicPoller = "package p;\npublic class Poller { /* deterministic @KafkaListener */ }";
        when(hybridConsumerTransformer.transform(any()))
                .thenReturn(new com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer.Result(
                        List.of(MigratedFile.builder()
                                .originalPath("src/p/Poller.java").newPath("src/p/Poller.java")
                                .content(deterministicPoller)
                                .changeType(FileChangeType.MODIFIED).diffSummary("deterministic transform").build()),
                        List.of()));
        // Generator adds the bridge file.
        String generatedBridge = "package p;\npublic final class CdiLookup { /* generated */ }";
        when(hybridScaffoldingGenerator.generate(any(), any())).thenAnswer(inv -> {
            List<MigratedFile> in = new java.util.ArrayList<>((List<MigratedFile>) inv.getArgument(0));
            in.add(MigratedFile.builder()
                    .originalPath("src/p/CdiLookup.java").newPath("src/p/CdiLookup.java")
                    .content(generatedBridge)
                    .changeType(FileChangeType.CREATED).diffSummary("Generated bridge").build());
            return in;
        });

        // Validator reports a violation; the repairer "rewrites" EVERY file.
        when(contractValidator.validate(any())).thenReturn(
                List.of(new com.altrix.orchestrator.domain.model.contract.ContractViolation(
                        com.altrix.orchestrator.domain.model.contract.ContractViolationKind.MISSING_IMPORT,
                        "src/p/Svc.java", 1, "X", "unresolved X")),
                List.of());
        when(contractRepairer.repair(any())).thenAnswer(inv -> {
            Map<String, String> in = inv.getArgument(0);
            Map<String, String> out = new java.util.LinkedHashMap<>();
            in.forEach((k, v) -> out.put(k, v + "\n// MANGLED BY REPAIR LLM"));
            return out;
        });

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(hybridPlan));

        Map<String, String> byPath = new java.util.LinkedHashMap<>();
        for (MigratedFile f : artifact.files()) byPath.put(f.newPath(), f.content());
        // Deterministic + generated content survives byte-identical.
        assertThat(byPath.get("src/p/Poller.java")).isEqualTo(deterministicPoller);
        assertThat(byPath.get("src/p/CdiLookup.java")).isEqualTo(generatedBridge);
        // The LLM file DID take the repair patch — the pass still works where allowed.
        assertThat(byPath.get("src/p/Svc.java")).contains("MANGLED BY REPAIR LLM");
    }

    @Test
    void execute_hybridTarget_wrapperKept_whenAnyConsumerBailedToLlm() {
        // A bailed @Schedule poller may still lean on the wrapper in its LLM
        // fallback migration — deletion must not fire.
        MigrationPlan hybridPlan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of(),
                JakartaMessagingTarget.SPRING_KAFKA_HYBRID);
        Map<String, String> files = jakartaFilesWith("package p;\npublic class Listener {}");
        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(Map.of("Listener.java", files.get("Listener.java")));
        pomReconcilerPassThrough();
        when(cdiStatelessConverter.convert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridScaffoldingGenerator.generate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridPublishRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridConsumerTransformer.transform(any()))
                .thenReturn(new com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer.Result(
                        List.of(),
                        List.of(new com.altrix.orchestrator.infrastructure.hybrid.HybridConsumerTransformer.Bail(
                                "Poller.java", "Poller", "no topic binding"))));

        agent.execute(ApprovedPlan.autoApproved(hybridPlan));

        verifyNoInteractions(pubSubWrapperRemover);
    }

    // ── output sanitization (#pom-prose bug) ──────────────────────────────

    @Test
    void stripLeadingProse_removesPreambleBeforeXml() {
        String raw = "Here is the migrated pom.xml:\n<?xml version=\"1.0\"?>\n<project></project>";
        String cleaned = CoreMigratorAgent.stripLeadingProse(raw, "pom.xml");
        assertThat(cleaned).startsWith("<?xml");
    }

    @Test
    void stripLeadingProse_leavesCleanXmlUntouched() {
        String raw = "<?xml version=\"1.0\"?>\n<project></project>";
        assertThat(CoreMigratorAgent.stripLeadingProse(raw, "pom.xml")).isEqualTo(raw);
    }

    @Test
    void stripLeadingProse_dropsPreambleBeforeJavaPackage() {
        String raw = "Sure! Here's the file:\npackage com.example;\nclass A {}";
        String cleaned = CoreMigratorAgent.stripLeadingProse(raw, "src/A.java");
        assertThat(cleaned).startsWith("package com.example;");
    }

    @Test
    void looksStructurallyBroken_flagsPomStartingWithProse() {
        assertThat(CoreMigratorAgent.looksStructurallyBroken(
                "Here is the pom <project></project>", "pom.xml")).isTrue();
        assertThat(CoreMigratorAgent.looksStructurallyBroken(
                "<project><artifactId>x</artifactId></project>", "pom.xml")).isFalse();
    }

    @Test
    void looksStructurallyBroken_flagsTruncatedPomWithNoClosingTag() {
        assertThat(CoreMigratorAgent.looksStructurallyBroken(
                "<project><dependencies>", "pom.xml")).isTrue();
    }

    @Test
    void looksStructurallyBroken_flagsDoubleDashInsideXmlComment() {
        // The exact failure pattern from the sandbox: em-dash normalised to "--"
        // inside a comment body — illegal XML, makes the POM non-parseable.
        String pom = "<project><!-- Guava -- Preconditions, Lists -->"
                + "<artifactId>x</artifactId></project>";
        assertThat(CoreMigratorAgent.looksStructurallyBroken(pom, "pom.xml")).isTrue();
    }

    @Test
    void repairXmlCommentDashes_collapsesDoubleDashInsideComment() {
        String broken = "<project><!-- Guava -- Preconditions, Lists --><x/></project>";
        String fixed = CoreMigratorAgent.repairXmlCommentDashes(broken);
        assertThat(fixed).isEqualTo("<project><!-- Guava - Preconditions, Lists --><x/></project>");
        assertThat(CoreMigratorAgent.looksStructurallyBroken(fixed, "pom.xml")).isFalse();
    }

    @Test
    void parsesAsXml_isResistantToDoctypeInjection() {
        // XXE-style payload — must not throw, must not fetch anything,
        // and must return false (the DOCTYPE itself is rejected).
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x SYSTEM \"http://example.com/evil.dtd\"><x/>";
        assertThat(CoreMigratorAgent.parsesAsXml(xxe)).isFalse();
    }

    // ── markdown contamination in Java output (real bug from PubsubSubscriptionType.java) ──

    /**
     * Reproduces the exact failure mode: the AI produced the enum,
     * closed it with ```, then continued with markdown prose and another
     * code block.  The old stripper only handled fences at the very start
     * or end, so the prose was carried into the staged .java file →
     * "illegal character: '`'" at compile time.
     */
    @Test
    void stripMarkdownFences_dropsTrailingProseAndSecondBlock() {
        String raw = """
                package com.example.altrix.pubsub;

                public enum PubsubSubscriptionType {
                    PULL,
                    PUSH
                }
                ```

                **Rationale for No Significant Change:**
                - The enum values `PULL` and `PUSH` are conceptually relevant.

                ```java
                package com.example.altrix.kafka; // ALTERNATIVE
                public enum X { A, B }
                ```
                """;
        String cleaned = CoreMigratorAgent.stripMarkdownFences(raw);
        assertThat(cleaned).doesNotContain("```");
        assertThat(cleaned).doesNotContain("**Rationale");
        assertThat(cleaned).doesNotContain("ALTERNATIVE");
        assertThat(cleaned).endsWith("}");
    }

    @Test
    void stripMarkdownFences_handlesLeadingFenceThenTrailingProse() {
        String raw = """
                ```java
                package com.example;
                class A {}
                ```

                Some explanation here.
                """;
        String cleaned = CoreMigratorAgent.stripMarkdownFences(raw);
        assertThat(cleaned).doesNotContain("```");
        assertThat(cleaned).doesNotContain("Some explanation");
        assertThat(cleaned).contains("class A");
    }

    @Test
    void hasMarkdownContamination_flagsResidualFenceOrBoldHeading() {
        String boldHeading = "package x;\n\n**Note:** something\nclass A {}";
        String stillFenced = "package x;\nclass A {}\n```\nmore";
        String clean       = "package x;\n\n/** Star comment */\nclass A {}";
        assertThat(CoreMigratorAgent.hasMarkdownContamination(boldHeading)).isTrue();
        assertThat(CoreMigratorAgent.hasMarkdownContamination(stillFenced)).isTrue();
        assertThat(CoreMigratorAgent.hasMarkdownContamination(clean)).isFalse();
    }

    @Test
    void looksStructurallyBroken_flagsContaminatedJava() {
        String dirty = "package x; class A {}\n```\n**Rationale:** …";
        assertThat(CoreMigratorAgent.looksStructurallyBroken(dirty, "src/A.java")).isTrue();
    }

    /**
     * Reproduces a real production failure: the AI returned pure English
     * prose explaining that no changes were needed, with no Java code at
     * all.  No triple-backticks, no markdown bold, but also no `package`
     * declaration and no type declaration — must be rejected so the
     * original file is staged instead.
     */
    @Test
    void hasMarkdownContamination_flagsProseOnlyResponseWithNoPackage() {
        String proseOnly = """
                Since the provided `PubsubSubscriptionType.java` file does not contain
                any direct references to Google Cloud Pub/Sub, the file does not require
                any modifications for the migration from Pub/Sub to Apache Kafka.

                Here is the file, returned exactly as provided, with no changes:
                """;
        assertThat(CoreMigratorAgent.hasMarkdownContamination(proseOnly)).isTrue();
    }

    @Test
    void hasMarkdownContamination_flagsJavaWithoutAnyTypeDeclaration() {
        // Has package + import but no class/interface/enum/record.
        String stripped = "package x;\nimport java.util.List;\n// nothing else";
        assertThat(CoreMigratorAgent.hasMarkdownContamination(stripped)).isTrue();
    }

    @Test
    void hasMarkdownContamination_passesCleanJava() {
        String clean = "package x;\n\npublic enum E { A, B }";
        assertThat(CoreMigratorAgent.hasMarkdownContamination(clean)).isFalse();
    }

    @Test
    void hasMarkdownContamination_passesCleanRecord() {
        String rec = "package x;\npublic record R(int x) {}";
        assertThat(CoreMigratorAgent.hasMarkdownContamination(rec)).isFalse();
    }

    // ── hallucinated-imports deny-list (real production case) ───────────────

    /**
     * Reproduces the exact failure pattern from
     * AcknowledgeMessagesTask.java in production: the model invented an
     * {@code OffsetCommitResult} that doesn't exist in kafka-clients.
     * The deny-list check must flag it so the file reverts to the
     * original instead of failing sandbox compile with "cannot find symbol".
     */
    @Test
    void firstDeniedImport_detectsOffsetCommitResultHallucination() {
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.OffsetCommitResult;
                public class X {}
                """;
        String hit = CoreMigratorAgent.firstDeniedImport(src,
                java.util.List.of("org.apache.kafka.clients.consumer.OffsetCommitResult"));
        assertThat(hit).isEqualTo("org.apache.kafka.clients.consumer.OffsetCommitResult");
    }

    @Test
    void firstDeniedImport_detectsWrongPackageKafkaException() {
        // org.apache.kafka.common.errors.KafkaException does NOT exist —
        // the real KafkaException lives at org.apache.kafka.common.KafkaException.
        String src = """
                package p;
                import org.apache.kafka.common.errors.KafkaException;
                public class X {}
                """;
        String hit = CoreMigratorAgent.firstDeniedImport(src,
                java.util.List.of("org.apache.kafka.common.errors.KafkaException"));
        assertThat(hit).isEqualTo("org.apache.kafka.common.errors.KafkaException");
    }

    @Test
    void firstDeniedImport_returnsNullForCleanFile() {
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.KafkaConsumer;
                import org.apache.kafka.common.KafkaException;
                public class X {}
                """;
        String hit = CoreMigratorAgent.firstDeniedImport(src, java.util.List.of(
                "org.apache.kafka.clients.consumer.OffsetCommitResult",
                "org.apache.kafka.common.errors.KafkaException"));
        assertThat(hit).isNull();
    }

    @Test
    void firstDeniedImport_handlesStaticImports() {
        String src = "package p;\nimport static p.Foo.BAR;\npublic class X {}";
        assertThat(CoreMigratorAgent.firstDeniedImport(src, java.util.List.of("p.Foo.BAR")))
                .isEqualTo("p.Foo.BAR");
    }

    @Test
    void firstDeniedImport_returnsNullOnEmptyDenyList() {
        String src = "package p;\nimport whatever.Anything;\npublic class X {}";
        assertThat(CoreMigratorAgent.firstDeniedImport(src, java.util.List.of())).isNull();
        assertThat(CoreMigratorAgent.firstDeniedImport(src, null)).isNull();
    }

    /**
     * New hallucination seen in the field: model invents
     * {@code org.apache.kafka.clients.consumer.ConsumerException} when
     * trying to translate Pub/Sub error handling.  Caught by an
     * explicit FQN in the deny-list.
     */
    @Test
    void firstDeniedImport_detectsConsumerExceptionHallucination() {
        String src = """
                package p;
                import org.apache.kafka.clients.consumer.ConsumerException;
                public class X {}
                """;
        String hit = CoreMigratorAgent.firstDeniedImport(src,
                java.util.List.of("org.apache.kafka.clients.consumer.ConsumerException"));
        assertThat(hit).isEqualTo("org.apache.kafka.clients.consumer.ConsumerException");
    }

    /**
     * The model invents an entire bogus package
     * {@code org.apache.kafka.common.security.auth.permission.*} when
     * mapping Pub/Sub IAM permission concepts.  The deny-list supports
     * a trailing {@code .*} wildcard so a single rule blocks all
     * classes in that fictional package.
     */
    @Test
    void firstDeniedImport_wildcardMatchesAnyClassInBogusPackage() {
        String src = """
                package p;
                import org.apache.kafka.common.security.auth.permission.AdminPermission;
                public class X {}
                """;
        String hit = CoreMigratorAgent.firstDeniedImport(src,
                java.util.List.of("org.apache.kafka.common.security.auth.permission.*"));
        assertThat(hit).isEqualTo("org.apache.kafka.common.security.auth.permission.AdminPermission");
    }

    @Test
    void firstDeniedImport_wildcardDoesNotMatchSiblingPackages() {
        // org.apache.kafka.common.security.auth.* (the real one) is NOT denied —
        // only the bogus .permission sub-package is.  Make sure the wildcard
        // is strict about the package boundary.
        String src = """
                package p;
                import org.apache.kafka.common.security.auth.SecurityProtocol;
                public class X {}
                """;
        String hit = CoreMigratorAgent.firstDeniedImport(src,
                java.util.List.of("org.apache.kafka.common.security.auth.permission.*"));
        assertThat(hit).isNull();
    }

    // ── publicTypeMismatchingFilename ──────────────────────────────────────

    /**
     * Reproduces the real failure: the model rewrites the interface body
     * and renames the type, but the file name is fixed at "IGoogleErrorConverter.java".
     * javac then aborts compilation of every dependent file.
     */
    @Test
    void publicTypeMismatchingFilename_detectsTypeRename() {
        String src = """
                package p;
                public interface IKafkaErrorConverter {
                    RuntimeException convert(Throwable cause);
                }""";
        String hit = CoreMigratorAgent.publicTypeMismatchingFilename(
                "src/main/java/p/IGoogleErrorConverter.java", src);
        assertThat(hit).isEqualTo("IKafkaErrorConverter");
    }

    @Test
    void publicTypeMismatchingFilename_passesWhenNamesMatch() {
        String src = "package p;\npublic class Foo {}";
        assertThat(CoreMigratorAgent.publicTypeMismatchingFilename("Foo.java", src)).isNull();
    }

    @Test
    void publicTypeMismatchingFilename_ignoresPackagePrivateTypes() {
        // Package-private types may carry any filename — only `public` is enforced.
        String src = "package p;\nclass Helper {}";
        assertThat(CoreMigratorAgent.publicTypeMismatchingFilename("Other.java", src)).isNull();
    }

    @Test
    void publicTypeMismatchingFilename_handlesWindowsPaths() {
        String src = "package p;\npublic class Wrong {}";
        assertThat(CoreMigratorAgent.publicTypeMismatchingFilename(
                "C:\\repo\\src\\main\\java\\p\\Foo.java", src))
                .isEqualTo("Wrong");
    }

    // ── firstUsedButNotImported ─────────────────────────────────────────────

    /**
     * The model writes `private final KafkaProducer<String,String> kafkaProducer`
     * but forgets the corresponding `import org.apache.kafka.clients.producer.KafkaProducer;`.
     * The class fails with "cannot find symbol class KafkaProducer".
     */
    @Test
    void firstUsedButNotImported_flagsKafkaProducerFieldWithoutImport() {
        String src = """
                package p;
                public class S {
                    private final KafkaProducer<String, String> producer = null;
                }""";
        assertThat(CoreMigratorAgent.firstUsedButNotImported(src)).isEqualTo("KafkaProducer");
    }

    @Test
    void firstUsedButNotImported_acceptsExplicitImport() {
        String src = """
                package p;
                import org.apache.kafka.clients.producer.KafkaProducer;
                public class S {
                    private final KafkaProducer<String, String> p = null;
                }""";
        assertThat(CoreMigratorAgent.firstUsedButNotImported(src)).isNull();
    }

    @Test
    void firstUsedButNotImported_acceptsWildcardImport() {
        String src = """
                package p;
                import org.apache.kafka.clients.producer.*;
                public class S {
                    private final KafkaProducer<String, String> p = null;
                }""";
        assertThat(CoreMigratorAgent.firstUsedButNotImported(src)).isNull();
    }

    @Test
    void firstUsedButNotImported_ignoresLocalDeclarations() {
        // If the file itself declares an inner class with the same simple name,
        // an explicit import would be a duplicate — don't flag the usage.
        String src = """
                package p;
                public class S {
                    private final KafkaProducer p = null;
                    static class KafkaProducer {}
                }""";
        assertThat(CoreMigratorAgent.firstUsedButNotImported(src)).isNull();
    }

    @Test
    void firstUsedButNotImported_returnsNullForCleanFile() {
        String src = """
                package p;
                public class S {
                    private final String name = "kafka";
                }""";
        assertThat(CoreMigratorAgent.firstUsedButNotImported(src)).isNull();
    }

    // ── stripLombokOnConstructor ────────────────────────────────────────────

    @Test
    void stripLombokOnConstructor_removesAnnotationParam() {
        String src = """
                package p;
                @AllArgsConstructor(onConstructor_ = @Inject)
                public class S {}""";
        String stripped = CoreMigratorAgent.stripLombokOnConstructor(src);
        assertThat(stripped).doesNotContain("onConstructor_");
        // The annotation itself stays; just the bad param is gone.
        assertThat(stripped).contains("@AllArgsConstructor");
        // Empty arg list collapsed — no dangling @AllArgsConstructor().
        assertThat(stripped).doesNotContain("@AllArgsConstructor(");
    }

    @Test
    void stripLombokOnConstructor_preservesOtherParams() {
        String src = "@AllArgsConstructor(access = AccessLevel.PROTECTED, onConstructor_ = @Inject)";
        String stripped = CoreMigratorAgent.stripLombokOnConstructor(src);
        assertThat(stripped).doesNotContain("onConstructor_");
        assertThat(stripped).contains("access = AccessLevel.PROTECTED");
    }

    @Test
    void stripLombokOnConstructor_noOpWhenAbsent() {
        String src = "@AllArgsConstructor\npublic class S {}";
        assertThat(CoreMigratorAgent.stripLombokOnConstructor(src)).isEqualTo(src);
    }
}
