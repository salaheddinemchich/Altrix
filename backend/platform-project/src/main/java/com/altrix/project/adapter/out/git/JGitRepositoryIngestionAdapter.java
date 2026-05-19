package com.altrix.project.adapter.out.git;

import com.altrix.common.exception.RepositoryIngestionException;
import com.altrix.project.domain.model.RepositorySnapshot;
import com.altrix.project.domain.port.out.RepositoryIngestionPort;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Driven adapter — clones remote Git repositories with JGit.
 *
 * <p>Issue #74. Clones into {@code ${app.workspace.base-dir}/<uuid>}, where the
 * default base directory is the OS temp dir + {@code /migrator-workspaces}. The
 * resulting working tree is always located under the configured base dir so the
 * sweeper in {@code WorkspaceCleanupScheduler} (issue #76 follow-up) can find
 * and remove stale clones.
 *
 * <p>Auth model (per parent issue #12):
 * <ul>
 *   <li>Public HTTPS — no credentials</li>
 *   <li>Private HTTPS — token used as the password with username {@code "x-access-token"}
 *       (GitHub PAT convention)</li>
 *   <li>SSH is intentionally out of scope</li>
 * </ul>
 *
 * <p>Size guard: after the working tree is materialised the adapter walks it
 * to compute the total on-disk size; clones exceeding {@code app.workspace.max-size-bytes}
 * (default 500 MB) are deleted immediately and reported via
 * {@link RepositoryIngestionException}.
 */
@Slf4j
@Component
public class JGitRepositoryIngestionAdapter implements RepositoryIngestionPort {

    /** Depth used when {@code CloneRequest.shallow() == true}. */
    private static final int SHALLOW_DEPTH = 50;

    /** Username sent alongside an HTTPS token (GitHub PAT convention). */
    private static final String TOKEN_USERNAME = "x-access-token";

    private final Path baseDir;
    private final long maxSizeBytes;

    public JGitRepositoryIngestionAdapter(
            @Value("${app.workspace.base-dir:#{systemProperties['java.io.tmpdir']}/migrator-workspaces}")
                    String baseDir,
            @Value("${app.workspace.max-size-bytes:524288000}") long maxSizeBytes
    ) {
        this.baseDir = Path.of(baseDir);
        this.maxSizeBytes = maxSizeBytes;
        ensureBaseDir();
    }

    @Override
    public RepositorySnapshot clone(CloneRequest request) {
        Path workspace = baseDir.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new RepositoryIngestionException(
                    "Could not create workspace directory: " + workspace, e);
        }

        CloneCommand cmd = Git.cloneRepository()
                .setURI(request.repoUrl())
                .setDirectory(workspace.toFile());

        if (request.branch() != null && !request.branch().isBlank()) {
            cmd.setBranch(request.branch());
        }
        if (request.shallow()) {
            cmd.setDepth(SHALLOW_DEPTH);
        }
        if (request.accessToken() != null && !request.accessToken().isBlank()) {
            cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(TOKEN_USERNAME, request.accessToken()));
        }

        log.info("Cloning {} (branch={}, shallow={}) → {}",
                request.repoUrl(), request.branch(), request.shallow(), workspace);

        String commitSha;
        String checkedOutBranch;
        long sizeBytes;
        try (Git git = cmd.call()) {
            commitSha = resolveHeadSha(git.getRepository());
            checkedOutBranch = resolveBranchName(git.getRepository(), request.branch());
            sizeBytes = computeSize(workspace);
        } catch (InvalidRemoteException e) {
            cleanup(workspace);
            throw new RepositoryIngestionException("Invalid remote URL: " + request.repoUrl(), e);
        } catch (TransportException e) {
            cleanup(workspace);
            throw new RepositoryIngestionException(
                    "Transport / auth failure cloning " + request.repoUrl()
                            + " — verify URL, branch, and access token", e);
        } catch (GitAPIException e) {
            cleanup(workspace);
            throw new RepositoryIngestionException(
                    "Git operation failed for " + request.repoUrl(), e);
        } catch (IOException e) {
            cleanup(workspace);
            throw new RepositoryIngestionException(
                    "I/O error while reading cloned repository at " + workspace, e);
        }

        // Size guard runs AFTER the Git instance is closed so that
        // RepositoryCache.clear() in cleanup() can release pack file mappings.
        if (sizeBytes > maxSizeBytes) {
            cleanup(workspace);
            throw new RepositoryIngestionException(
                    "Repository exceeds size limit: " + sizeBytes + " > " + maxSizeBytes + " bytes");
        }

        log.info("Cloned {} @ {} ({} bytes)", request.repoUrl(), commitSha, sizeBytes);

        return new RepositorySnapshot(
                workspace,
                request.repoUrl(),
                checkedOutBranch,
                commitSha,
                sizeBytes,
                request.shallow(),
                Instant.now()
        );
    }

    @Override
    public void cleanup(Path workspacePath) {
        if (workspacePath == null || !Files.exists(workspacePath)) return;
        // On Windows, JGit's WindowCache keeps memory-mapped pack files even
        // after Git.close(); without evicting them, FileUtils.delete fails with
        // "The process cannot access the file because it is being used by another
        // process". Clearing the repository cache releases the mappings.
        RepositoryCache.clear();
        try {
            FileUtils.delete(
                    workspacePath.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException e) {
            log.warn("Workspace cleanup failed for {}: {}", workspacePath, e.getMessage());
        }
    }

    private void ensureBaseDir() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot create workspace base directory: " + baseDir, e);
        }
    }

    private static String resolveHeadSha(Repository repo) throws IOException {
        return repo.resolve("HEAD").getName();
    }

    private static String resolveBranchName(Repository repo, String requested) throws IOException {
        if (requested != null && !requested.isBlank()) return requested;
        Ref head = repo.exactRef("HEAD");
        String target = head.getTarget().getName();
        return target.startsWith("refs/heads/")
                ? target.substring("refs/heads/".length())
                : target;
    }

    private static long computeSize(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(JGitRepositoryIngestionAdapter::sizeQuietly)
                    .sum();
        }
    }

    private static long sizeQuietly(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }
}
