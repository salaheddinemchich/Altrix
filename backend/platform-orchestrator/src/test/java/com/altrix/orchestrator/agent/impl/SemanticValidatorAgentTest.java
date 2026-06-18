package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.MigratedFile;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.orchestrator.domain.model.semantic.SemanticValidationReport.Category;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.contract.ContractValidator;
import com.altrix.orchestrator.infrastructure.leak.PubSubLeakValidator;
import com.altrix.orchestrator.infrastructure.semantic.DependencyValidator;
import com.altrix.orchestrator.infrastructure.semantic.DeterministicRepairEngine;
import com.altrix.orchestrator.infrastructure.semantic.JavaxToJakartaRewriter;
import com.altrix.orchestrator.infrastructure.semantic.LombokConstructorReconciler;
import com.altrix.orchestrator.infrastructure.semantic.MessagingConfigRepairer;
import com.altrix.orchestrator.infrastructure.semantic.SpringKafkaOverEngineeringDetector;
import com.altrix.orchestrator.infrastructure.semantic.SpringValueConstructorInjectionFixer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses the REAL ContractValidator + PubSubLeakValidator (they're pure,
 * JavaParser-based) plus a small hand-built KnowledgeBase, so the test
 * exercises the actual aggregation logic — only the artifact plumbing is
 * synthetic.
 */
class SemanticValidatorAgentTest {

    private final ContractValidator contractValidator = new ContractValidator();
    private final PubSubLeakValidator leakValidator = new PubSubLeakValidator();

    private KafkaMigrationKnowledgeBase kb(List<String> globalForbidden) {
        return new KafkaMigrationKnowledgeBase(List.of(), List.of(), globalForbidden, List.of());
    }

    private SemanticValidatorAgent agent(KafkaMigrationKnowledgeBase kb) {
        return new SemanticValidatorAgent(contractValidator, leakValidator, kb,
                new DeterministicRepairEngine(kb), new DependencyValidator(kb),
                new JavaxToJakartaRewriter(), new LombokConstructorReconciler(),
                new SpringValueConstructorInjectionFixer(), new MessagingConfigRepairer(),
                new SpringKafkaOverEngineeringDetector());
    }

    private MigratedFile java(String path, String content) {
        return MigratedFile.builder()
                .originalPath(path).newPath(path).content(content)
                .changeType(FileChangeType.MODIFIED).diffSummary("x").build();
    }

    @Test
    void cleanArtifactProducesCleanReportAndPassesThrough() {
        var agent = agent(kb(List.of()));
        var artifact = new MigrationArtifact("p1", List.of(
                java("p/Foo.java", "package p;\nimport org.apache.kafka.clients.producer.KafkaProducer;\npublic class Foo {}")),
                "ok");

        MigrationArtifact out = agent.execute(artifact);

        // Stage 3 — artifact passes through unchanged.
        assertThat(out).isSameAs(artifact);
    }

    @Test
    void detectsPubSubLeak() {
        var agent = agent(kb(List.of()));
        var report = agent.validate("p1", Map.of(
                "p/PullTask.java",
                "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class PullTask {}"));

        assertThat(report.clean()).isFalse();
        assertThat(report.countOf(Category.PUBSUB_LEAK)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void detectsContractViolation() {
        var agent = agent(kb(List.of()));
        // File name says Foo.java but the public type is Bar → FILE_CLASS_MISMATCH.
        var report = agent.validate("p1", Map.of(
                "p/Foo.java", "package p;\npublic class Bar {}"));

        assertThat(report.clean()).isFalse();
        assertThat(report.countOf(Category.CONTRACT)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void detectsForbiddenImportFromKnowledgeBase() {
        var agent = agent(kb(List.of(
                "org.apache.kafka.clients.consumer.OffsetCommitResult",
                "org.apache.kafka.common.security.auth.permission.*")));
        var report = agent.validate("p1", Map.of(
                "p/A.java",
                "package p;\nimport org.apache.kafka.clients.consumer.OffsetCommitResult;\npublic class A {}",
                "p/B.java",
                "package p;\nimport org.apache.kafka.common.security.auth.permission.AdminPermission;\npublic class B {}"));

        assertThat(report.countOf(Category.FORBIDDEN_IMPORT)).isEqualTo(2);  // exact + wildcard
    }

    @Test
    void forbiddenImportWildcardRespectsPackageBoundary() {
        var agent = agent(kb(List.of("org.apache.kafka.common.security.auth.permission.*")));
        // A sibling package (NOT .permission.) must not match.
        var report = agent.validate("p1", Map.of(
                "p/A.java",
                "package p;\nimport org.apache.kafka.common.security.auth.SecurityProtocol;\npublic class A {}"));

        assertThat(report.countOf(Category.FORBIDDEN_IMPORT)).isZero();
    }

    @Test
    void emptyJavaFilesIsClean() {
        var agent = agent(kb(List.of()));
        assertThat(agent.validate("p1", Map.of()).clean()).isTrue();
    }

    @Test
    void nonJavaFilesAreIgnored() {
        var agent = agent(kb(List.of()));
        var artifact = new MigrationArtifact("p1", List.of(
                MigratedFile.builder().originalPath("pom.xml").newPath("pom.xml")
                        .content("<project/>").changeType(FileChangeType.MODIFIED)
                        .diffSummary("x").build()),
                "ok");
        // No java files → nothing to validate → pass-through, clean.
        assertThat(agent.execute(artifact)).isSameAs(artifact);
    }

    @Test
    void exposesNameAndOrder() {
        var agent = agent(kb(List.of()));
        assertThat(agent.getName()).isEqualTo("Semantic Validator");
        assertThat(agent.getOrder()).isEqualTo(3);
    }

    @Test
    void detectsMissingKafkaDependencyViaPom() {
        var kbWithDeps = new KafkaMigrationKnowledgeBase(List.of(),
                List.of(new KafkaMigrationKnowledgeBase.ClassDependency(
                        "org.apache.kafka.clients.consumer.*", "org.apache.kafka:kafka-clients")),
                List.of(), List.of());
        var agent = agent(kbWithDeps);
        var report = agent.validate("p1", Map.of(
                        "p/S.java",
                        "package p;\nimport org.apache.kafka.clients.consumer.KafkaConsumer;\npublic class S {}"),
                "<project><dependencies></dependencies></project>");
        assertThat(report.countOf(Category.MISSING_DEPENDENCY)).isEqualTo(1);
    }

    @Test
    void executeAppliesDeterministicImportRepair() {
        // KB that knows KafkaConsumer's import.
        var mapping = new KafkaMigrationKnowledgeBase.Mapping(
                "pubsub.pull", "consumer.poll(...)",
                List.of(),
                List.of("org.apache.kafka.clients.consumer.KafkaConsumer"),
                List.of(), List.of(), null);
        var kbWithImports = new KafkaMigrationKnowledgeBase(List.of(mapping), List.of(), List.of(), List.of());
        var agent = agent(kbWithImports);

        // File uses KafkaConsumer but doesn't import it.
        var artifact = new MigrationArtifact("p1", List.of(
                java("p/S.java", "package p;\npublic class S { KafkaConsumer<String,String> c; }")),
                "ok");

        MigrationArtifact out = agent.execute(artifact);

        // Artifact was modified — the missing import was added deterministically.
        assertThat(out).isNotSameAs(artifact);
        assertThat(out.files().get(0).content())
                .contains("import org.apache.kafka.clients.consumer.KafkaConsumer;");
    }
}
