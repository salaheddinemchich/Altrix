package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @InjectMocks
    CoreMigratorAgent agent;

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
                List.of("Step 1"), "MEDIUM", "2 days", "migrate messaging", List.of());
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
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of());
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
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "", List.of());
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
                List.of("Listener.java"));
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
}
