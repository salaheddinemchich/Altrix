package com.altrix.project.adapter.out.git;

import com.altrix.common.exception.RepositoryIngestionException;
import com.altrix.project.domain.model.RepositorySnapshot;
import com.altrix.project.domain.port.out.RepositoryIngestionPort.CloneRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JGitRepositoryIngestionAdapter} against a local
 * file-system bare repository.  Avoids any network access.
 */
class JGitRepositoryIngestionAdapterTest {

    @TempDir Path tmp;

    private Path remoteBare;
    private String remoteUrl;
    private JGitRepositoryIngestionAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        remoteBare = tmp.resolve("remote.git");
        seedBareRepoWithOneCommit(remoteBare);
        // file:// URI lets JGit clone via the local filesystem transport
        remoteUrl = remoteBare.toUri().toString();

        Path workspaceRoot = tmp.resolve("workspaces");
        adapter = new JGitRepositoryIngestionAdapter(workspaceRoot.toString(), 524_288_000L);
    }

    @AfterEach
    void tearDown() {
        // workspace root is under @TempDir — JUnit cleans it
    }

    @Test
    void clones_public_repo_into_fresh_workspace() {
        RepositorySnapshot snapshot = adapter.clone(CloneRequest.publicHead(remoteUrl));

        assertThat(snapshot.workspacePath()).exists().isDirectory();
        assertThat(snapshot.workspacePath().resolve("README.md")).exists();
        assertThat(snapshot.remoteUrl()).isEqualTo(remoteUrl);
        assertThat(snapshot.commitSha()).hasSize(40).matches("[0-9a-f]+");
        assertThat(snapshot.sizeBytes()).isPositive();
        assertThat(snapshot.shallow()).isFalse();
        assertThat(snapshot.clonedAt()).isNotNull();
    }

    @Test
    void each_clone_uses_a_unique_workspace_subdirectory() {
        RepositorySnapshot a = adapter.clone(CloneRequest.publicHead(remoteUrl));
        RepositorySnapshot b = adapter.clone(CloneRequest.publicHead(remoteUrl));

        assertThat(a.workspacePath()).isNotEqualTo(b.workspacePath());
    }

    @Test
    void shallow_flag_is_propagated_to_the_snapshot() {
        // Note: JGit's file:// transport silently ignores --depth=N — only smart
        // protocols (https://, ssh://, git://) actually produce a shallow clone.
        // We can only verify the flag is propagated here; real shallow behaviour
        // requires an integration test against an HTTP server.
        RepositorySnapshot snapshot = adapter.clone(
                new CloneRequest(remoteUrl, null, null, true));

        assertThat(snapshot.shallow()).isTrue();
        assertThat(snapshot.workspacePath()).exists().isDirectory();
    }

    @Test
    void invalid_url_throws_RepositoryIngestionException() {
        CloneRequest req = CloneRequest.publicHead(
                tmp.resolve("does-not-exist.git").toUri().toString());

        assertThatThrownBy(() -> adapter.clone(req))
                .isInstanceOf(RepositoryIngestionException.class);
    }

    @Test
    void size_limit_is_enforced_and_workspace_is_cleaned_up() throws Exception {
        // tiny limit → any non-empty clone exceeds it
        Path workspaceRoot = tmp.resolve("ws-tiny");
        JGitRepositoryIngestionAdapter tinyAdapter =
                new JGitRepositoryIngestionAdapter(workspaceRoot.toString(), 1L);

        assertThatThrownBy(() -> tinyAdapter.clone(CloneRequest.publicHead(remoteUrl)))
                .isInstanceOf(RepositoryIngestionException.class)
                .hasMessageContaining("exceeds size limit");

        // every sub-directory created during the failed clone must be gone
        try (var stream = Files.list(workspaceRoot)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void cleanup_removes_the_workspace_recursively() throws Exception {
        RepositorySnapshot snapshot = adapter.clone(CloneRequest.publicHead(remoteUrl));
        assertThat(snapshot.workspacePath()).exists();

        adapter.cleanup(snapshot.workspacePath());

        assertThat(snapshot.workspacePath()).doesNotExist();
    }

    @Test
    void cleanup_is_idempotent_on_missing_path() {
        // must not throw
        adapter.cleanup(tmp.resolve("never-existed"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void seedBareRepoWithOneCommit(Path bareRepoPath) throws Exception {
        // 1. init a working copy
        Path work = bareRepoPath.resolveSibling("seed-work");
        Files.createDirectories(work);
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("README.md"), "# test\nhello\n");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor(new PersonIdent("test", "test@example.com"))
                    .call();

            // 2. clone --bare into the destination so it is a valid push target
            try (Git ignored = Git.cloneRepository()
                    .setBare(true)
                    .setURI(work.toUri().toString())
                    .setDirectory(bareRepoPath.toFile())
                    .call()) {
                // bare clone created
            }
        }

        // 3. nuke the temporary working copy
        deleteRecursively(work);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }
}
