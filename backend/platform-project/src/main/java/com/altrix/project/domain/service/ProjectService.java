package com.altrix.project.domain.service;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.exception.ProjectNotFoundException;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.port.in.GetProjectQuery;
import com.altrix.project.domain.port.in.UploadProjectUseCase;
import com.altrix.project.domain.port.out.FileStoragePort;
import com.altrix.project.domain.port.out.ProjectEventPublisherPort;
import com.altrix.project.domain.port.out.ProjectRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ProjectService implements UploadProjectUseCase, GetProjectQuery {

    private final ProjectRepositoryPort     projectRepository;
    private final FileStoragePort           fileStoragePort;
    private final ProjectEventPublisherPort eventPublisher;
    private final BuildSystemDetector   buildSystemDetector;

    @Override
    public Project upload(
            String userId,
            String fileName,
            InputStream zipContent,
            long fileSizeBytes,
            ConfigFormatPreference configFormatPreference
    ) {
        log.info("Uploading project '{}' for user '{}'", fileName, userId);

        // 1. Store ZIP — domain does not know it's MinIO
        String storageKey = fileStoragePort.store(zipContent, fileSizeBytes, "application/zip");
        log.debug("Stored ZIP at storage key '{}'", storageKey);

        // 2. Create the domain entity in PENDING status
        Project project = Project.create(userId, fileName, storageKey, configFormatPreference);

        // 3. Retrieve the stored ZIP as a stream and run detection
        try (InputStream zipStream = fileStoragePort.retrieve(storageKey)) {
            project = buildSystemDetector.detect(project, zipStream);
        } catch (Exception e) {
            log.error("Detection failed for project '{}': {}", project.getName(), e.getMessage());
            project = project.withError();
            projectRepository.save(project);
            throw new RuntimeException("Detection failed: " + e.getMessage(), e);
        }

        log.info("Detected: framework={}, buildSystem={}, configFormat={}",
                project.getFramework(), project.getBuildSystem(), project.getConfigFormat());

        // 4. Persist
        project = projectRepository.save(project);
        log.info("Project registered with id '{}'", project.getId());

        // 5. Publish domain event — domain does not know it's Kafka
        eventPublisher.publishProjectRegistered(project);

        return project;
    }

    @Override
    public Project findById(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    @Override
    public List<Project> findAllByUserId(String userId) {
        return projectRepository.findAllByUserId(userId);
    }
}
