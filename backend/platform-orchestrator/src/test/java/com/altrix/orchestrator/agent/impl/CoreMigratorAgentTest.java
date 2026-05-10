package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.PrunedContext;
import com.altrix.orchestrator.domain.port.out.AiPort;
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
        Map<String, String> files = Map.of(
                "Listener.java", "import google.cloud.pubsub; class Listener {}");

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(files);
        prunerPassThrough(files);
        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("import org.springframework.kafka; class Listener {}");

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
                "Listener.java", "import google.cloud.pubsub; class Listener {}",
                "Service.java", "class Service {}"
        );
        Map<String, String> pruned = Map.of("Listener.java", allFiles.get("Listener.java"));

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(allFiles);
        when(contextPruner.prune(any(), any()))
                .thenReturn(new PrunedContext(pruned, 2, 1));
        when(aiPort.chat(anyString(), anyString()))
                .thenReturn("import org.springframework.kafka; class Listener {}");

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
}
