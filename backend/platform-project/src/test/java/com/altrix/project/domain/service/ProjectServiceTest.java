package com.altrix.project.domain.service;

import com.altrix.common.domain.enums.BuildSystem;
import com.altrix.common.domain.enums.ConfigFormat;
import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.DetectedFramework;
import com.altrix.common.exception.ProjectNotFoundException;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectStatus;
import com.altrix.project.domain.port.out.FileStoragePort;
import com.altrix.project.domain.port.out.ProjectEventPublisher;
import com.altrix.project.domain.port.out.ProjectRepository;
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

    @Mock ProjectRepository    projectRepository;
    @Mock FileStoragePort      fileStoragePort;
    @Mock ProjectEventPublisher eventPublisher;
    @Mock BuildSystemDetector  buildSystemDetector;

    ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository, fileStoragePort, eventPublisher, buildSystemDetector);
    }

    @Test
    void upload_storesFileDetectsAndPublishes() throws Exception {
        InputStream zip = new ByteArrayInputStream(new byte[]{});
        when(fileStoragePort.store(any(), anyLong(), any())).thenReturn("uploads/key.zip");

        Project detected = Project.create("user-1", "app.zip", "uploads/key.zip", null)
                .withDetectionApplied(BuildSystem.GRADLE_KOTLIN, ConfigFormat.YAML, DetectedFramework.SPRING_BOOT);
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
}
