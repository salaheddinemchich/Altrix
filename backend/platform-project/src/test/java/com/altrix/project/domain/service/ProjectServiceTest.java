package com.altrix.project.domain.service;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.common.exception.ProjectNotFoundException;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectStatus;
import com.altrix.project.domain.port.out.FileStoragePort;
import com.altrix.project.domain.port.out.ProjectEventPublisherPort;
import com.altrix.project.domain.port.out.ProjectRepositoryPort;
import com.altrix.project.domain.port.out.RepositoryIngestionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock ProjectRepositoryPort     projectRepository;
    @Mock FileStoragePort           fileStoragePort;
    @Mock ProjectEventPublisherPort eventPublisher;
    @Mock BuildSystemDetector       buildSystemDetector;
    @Mock RepositoryIngestionPort   repositoryIngestion;

    ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository, fileStoragePort, eventPublisher,
                buildSystemDetector, repositoryIngestion);
    }

    @Test
    void upload_storesFileDetectsAndPublishes() throws Exception {
        InputStream zip = new ByteArrayInputStream(new byte[]{});
        when(fileStoragePort.store(any(), anyLong(), any())).thenReturn("uploads/key.zip");

        Project detected = Project.create("user-1", "app.zip", "uploads/key.zip", null)
                .withDetectionApplied(BuildSystem.GRADLE_KOTLIN, ConfigFormat.YAML, DetectedFramework.SPRING_BOOT,
                        true, java.util.List.of("SPRING_BOOT", "GRADLE_KOTLIN", "GCP_PUBSUB"));
        when(fileStoragePort.retrieve("uploads/key.zip")).thenReturn(zip);
        when(buildSystemDetector.detect(any(), any(InputStream.class))).thenReturn(detected);
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.upload(
                "user-1", "app.zip", zip, 100L, ConfigFormatPreference.KEEP_ORIGINAL);

        assertThat(result.getStatus()).isEqualTo(ProjectStatus.READY);
        verify(fileStoragePort).store(any(), anyLong(), eq("application/zip"));
        verify(eventPublisher).publishProjectRegistered(any());
    }

    @Test
    void findById_returnsProject_whenExists() {
        Project project = Project.create("user-1", "app.zip", "key", null);
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));

        Project found = projectService.findById(project.getId());
        assertThat(found.getId()).isEqualTo(project.getId());
    }

    @Test
    void findById_throws_whenNotFound() {
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.findById("missing"))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void findAllByUserId_returnsList() {
        Project p = Project.create("user-1", "app.zip", "key", null);
        when(projectRepository.findAllByUserId("user-1")).thenReturn(List.of(p));

        List<Project> results = projectService.findAllByUserId("user-1");
        assertThat(results).hasSize(1);
    }

    @Test
    void upload_savesErrorProject_andRethrows_whenDetectionFails() throws Exception {
        InputStream zip = new ByteArrayInputStream(new byte[]{});
        when(fileStoragePort.store(any(), anyLong(), any())).thenReturn("uploads/key.zip");
        when(fileStoragePort.retrieve("uploads/key.zip")).thenReturn(zip);
        when(buildSystemDetector.detect(any(), any())).thenThrow(new RuntimeException("bad zip"));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> projectService.upload("user-1", "app.zip", zip, 100L, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Detection failed");

        verify(projectRepository).save(argThat(p -> p.getStatus() == ProjectStatus.ERROR));
    }

    // ── Issue #12: git ingestion ──────────────────────────────────────────────

    @Test
    void ingestFromGit_clones_zips_detects_persists_publishes_andCleansUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // 1. Fake workspace on disk: a single README.md (BuildSystemDetector is mocked)
        java.nio.file.Path workspace = tmp.resolve("clone");
        java.nio.file.Files.createDirectories(workspace);
        java.nio.file.Files.writeString(workspace.resolve("README.md"), "# project\n");

        com.altrix.project.domain.model.RepositorySnapshot snapshot =
                new com.altrix.project.domain.model.RepositorySnapshot(
                        workspace,
                        "https://github.com/acme/widgets.git",
                        "main",
                        "0".repeat(40),
                        12L,
                        false,
                        java.time.Instant.now());

        when(repositoryIngestion.clone(any())).thenReturn(snapshot);
        when(fileStoragePort.store(any(), anyLong(), any())).thenReturn("uploads/clone-key.zip");

        Project detected = Project.create("user-1", "widgets", "uploads/clone-key.zip", null)
                .withDetectionApplied(BuildSystem.MAVEN, ConfigFormat.YAML, DetectedFramework.SPRING_BOOT,
                        false, List.of("SPRING_BOOT"));
        when(buildSystemDetector.detect(any(), any(InputStream.class))).thenReturn(detected);
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.ingestFromGit(
                new com.altrix.project.domain.port.in.IngestGitRepositoryUseCase.GitIngestionCommand(
                        "user-1",
                        "https://github.com/acme/widgets.git",
                        "main",
                        null,
                        false,
                        ConfigFormatPreference.KEEP_ORIGINAL));

        // Repo URL → project name (.git stripped)
        assertThat(result.getName()).isEqualTo("widgets");
        assertThat(result.getStatus()).isEqualTo(ProjectStatus.READY);

        // ZIP is stored exactly once with application/zip content-type
        verify(fileStoragePort).store(any(), anyLong(), eq("application/zip"));
        // Domain event fired
        verify(eventPublisher).publishProjectRegistered(any());
        // Workspace is cleaned up after success
        verify(repositoryIngestion).cleanup(workspace);
    }

    @Test
    void ingestFromGit_cleansUpWorkspace_evenWhenDetectionFails(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path workspace = tmp.resolve("clone");
        java.nio.file.Files.createDirectories(workspace);
        java.nio.file.Files.writeString(workspace.resolve("pom.xml"), "<project/>");

        when(repositoryIngestion.clone(any())).thenReturn(new com.altrix.project.domain.model.RepositorySnapshot(
                workspace, "https://example.com/r.git", "main", "0".repeat(40),
                10L, false, java.time.Instant.now()));
        when(fileStoragePort.store(any(), anyLong(), any())).thenReturn("k");
        when(buildSystemDetector.detect(any(), any())).thenThrow(new RuntimeException("boom"));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> projectService.ingestFromGit(
                new com.altrix.project.domain.port.in.IngestGitRepositoryUseCase.GitIngestionCommand(
                        "u", "https://example.com/r.git", null, null, false, null)))
                .isInstanceOf(RuntimeException.class);

        // Project saved in ERROR state, workspace always cleaned up
        verify(projectRepository).save(argThat(p -> p.getStatus() == ProjectStatus.ERROR));
        verify(repositoryIngestion).cleanup(workspace);
    }

    @Test
    void zipWorkspace_skipsGitDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path workspace = tmp.resolve("ws");
        java.nio.file.Files.createDirectories(workspace.resolve(".git/objects"));
        java.nio.file.Files.writeString(workspace.resolve(".git/HEAD"), "ref: refs/heads/main");
        java.nio.file.Files.writeString(workspace.resolve("src.java"), "class A {}");

        byte[] zipped = ProjectService.zipWorkspace(workspace);

        // Read entries back — only src.java should appear, never anything under .git
        try (var zin = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipped))) {
            java.util.List<String> entries = new java.util.ArrayList<>();
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) entries.add(e.getName());
            assertThat(entries).contains("src.java");
            assertThat(entries).noneMatch(name -> name.startsWith(".git"));
        }
    }
}
