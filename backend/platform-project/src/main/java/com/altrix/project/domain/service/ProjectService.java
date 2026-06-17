package com.altrix.project.domain.service;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.exception.ProjectNotFoundException;
import com.altrix.common.exception.RepositoryIngestionException;
import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.model.ProjectSource;
import com.altrix.project.domain.model.RepositorySnapshot;
import com.altrix.project.domain.port.in.GetProjectQuery;
import com.altrix.project.domain.port.in.IngestGitRepositoryUseCase;
import com.altrix.project.domain.port.in.UploadProjectUseCase;
import com.altrix.project.domain.port.out.FileStoragePort;
import com.altrix.project.domain.port.out.ProjectEventPublisherPort;
import com.altrix.project.domain.port.out.ProjectRepositoryPort;
import com.altrix.project.domain.port.out.RepositoryIngestionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RequiredArgsConstructor
public class ProjectService implements UploadProjectUseCase, GetProjectQuery, IngestGitRepositoryUseCase {

    private final ProjectRepositoryPort projectRepository;
    private final FileStoragePort fileStoragePort;
    private final ProjectEventPublisherPort eventPublisher;
    private final BuildSystemDetector buildSystemDetector;
    private final RepositoryIngestionPort repositoryIngestion;

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

    @Override
    public Project ingestFromGit(GitIngestionCommand cmd) {
        log.info("Cloning '{}' (branch={}) for user '{}'",
                cmd.repoUrl(), cmd.branch(), cmd.userId());

        var cloneReq = new RepositoryIngestionPort.CloneRequest(
                cmd.repoUrl(), cmd.branch(), cmd.accessToken(), cmd.shallow());
        RepositorySnapshot snapshot = repositoryIngestion.clone(cloneReq);

        try {
            // 1. Re-package the working tree as a ZIP and stage it in MinIO so
            //    the rest of the pipeline (BuildSystemDetector, orchestrator
            //    download, MigrationCoordinator) is unchanged.
            byte[] zipBytes = zipWorkspace(snapshot.workspacePath());
            String storageKey;
            try (InputStream zipStream = new ByteArrayInputStream(zipBytes)) {
                storageKey = fileStoragePort.store(zipStream, zipBytes.length, "application/zip");
            }
            log.debug("Stored cloned repo ZIP at '{}'", storageKey);

            // 2. Project name = last path segment of the URL, sans .git
            String name = deriveProjectName(cmd.repoUrl());

            // 3. Create + detect + persist + publish — mirrors the upload flow.
            //    createFromGit records repoUrl + tracked branch + source so the
            //    webhook auto-trigger consumer (#90) can later find this row.
            Project project = Project.createFromGit(
                    cmd.userId(),
                    name,
                    storageKey,
                    cmd.configFormatPreference(),
                    cmd.repoUrl(),
                    snapshot.branch(),
                    ProjectSource.GIT_CLONE);

            try (InputStream zipStream = new ByteArrayInputStream(zipBytes)) {
                project = buildSystemDetector.detect(project, zipStream);
            } catch (Exception e) {
                log.error("Detection failed for '{}': {}", name, e.getMessage());
                projectRepository.save(project.withError());
                throw new RuntimeException("Detection failed: " + e.getMessage(), e);
            }

            project = projectRepository.save(project);
            eventPublisher.publishProjectRegistered(project);

            log.info("Ingested git project id='{}' from '{}' @ {}",
                    project.getId(), cmd.repoUrl(), snapshot.commitSha());
            return project;

        } catch (IOException e) {
            throw new RepositoryIngestionException(
                    "Failed to read cloned workspace at " + snapshot.workspacePath(), e);
        } finally {
            // Workspace is staging-only — drop it now that the ZIP is in MinIO.
            repositoryIngestion.cleanup(snapshot.workspacePath());
        }
    }

    /**
     * Walks {@code workspacePath} and writes every regular file (skipping
     * {@code .git}) into a ZIP byte array suitable for streaming into MinIO.
     */
    static byte[] zipWorkspace(Path workspacePath) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> walk = Files.walk(workspacePath)) {

            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(p)) continue;
                Path rel = workspacePath.relativize(p);
                // .git is not source — every adapter that reads the ZIP would
                // explode the heap trying to parse pack files.
                if (rel.getName(0).toString().equals(".git")) continue;

                zip.putNextEntry(new ZipEntry(rel.toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * "https://github.com/user/repo.git" -> "repo"
     */
    private static String deriveProjectName(String repoUrl) {
        String tail = repoUrl.endsWith("/")
                ? repoUrl.substring(0, repoUrl.length() - 1)
                : repoUrl;
        int slash = tail.lastIndexOf('/');
        String segment = slash >= 0 ? tail.substring(slash + 1) : tail;
        return segment.endsWith(".git")
                ? segment.substring(0, segment.length() - 4)
                : segment;
    }
}
