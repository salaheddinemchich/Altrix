package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.enums.FileChangeType;
import com.altrix.common.domain.model.ApprovedPlan;
import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.MigrationPlan;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.port.out.AiPort;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoreMigratorAgentTest {

    @Mock AiPort         aiPort;
    @Mock FileReaderPort fileReader;
    @InjectMocks CoreMigratorAgent agent;

    @Test
    void exposesNameAndOrder3() {
        assertThat(agent.getName()).isEqualTo("Core Migrator");
        assertThat(agent.getOrder()).isEqualTo(3);
    }

    @Test
    void execute_withStorageKey_migratesFilesWithPubSubCode() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "Spring Boot 3 + Kafka",
                List.of("Step 1"), "MEDIUM", "2 days", "migrate messaging");
        ApprovedPlan approved = ApprovedPlan.autoApproved(plan);

        when(fileReader.readSourceFiles("uploads/p1.zip")).thenReturn(Map.of(
                "Listener.java", "import google.cloud.pubsub; class Listener {}"));
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
        verifyNoInteractions(aiPort, fileReader);
    }

    @Test
    void execute_fileWithNoPubSubCode_passedThrough_unchanged() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "");
        when(fileReader.readSourceFiles("uploads/p1.zip"))
                .thenReturn(Map.of("Service.java", "import java.util.List; class Service {}"));

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(artifact.files()).hasSize(1);
        assertThat(artifact.files().get(0).changeType()).isEqualTo(FileChangeType.UNCHANGED);
        verifyNoInteractions(aiPort);
    }

    @Test
    void execute_aiFails_keepsOriginalFile_andContinues() {
        MigrationPlan plan = new MigrationPlan("p1", "uploads/p1.zip", "", List.of(), "", "", "");
        when(fileReader.readSourceFiles("uploads/p1.zip"))
                .thenReturn(Map.of("Broken.java", "import google.cloud.pubsub; class Broken {}"));
        when(aiPort.chat(anyString(), anyString())).thenThrow(new RuntimeException("AI timeout"));

        MigrationArtifact artifact = agent.execute(ApprovedPlan.autoApproved(plan));

        assertThat(artifact.files()).hasSize(1);
        assertThat(artifact.files().get(0).changeType()).isEqualTo(FileChangeType.UNCHANGED);
        assertThat(artifact.files().get(0).diffSummary()).contains("AI unavailable");
    }

    @Test
    void execute_nullInput_throwsAgentFailureException() {
        assertThatThrownBy(() -> agent.execute(null))
                .isInstanceOf(AgentFailureException.class);
    }
}
