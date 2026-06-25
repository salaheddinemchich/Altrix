package com.altrix.orchestrator.adapter.out.github;

import com.altrix.orchestrator.domain.model.github.GitHubRepo;
import com.altrix.orchestrator.domain.port.out.RepositoryProviderPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Calls the GitHub REST API on behalf of an authenticated user.
 * All calls use the user's decrypted OAuth token so results are scoped
 * to that user's accessible repositories (private + public).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubApiClient {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String REPOS_URL = GITHUB_API_BASE + "/user/repos?per_page=100&sort=updated&type=all";
    // {owner} and {repo} are separate path vars — using one {fullName} causes
    // RestTemplate to URL-encode the slash and GitHub returns 404.
    private static final String ZIP_URL_TEMPLATE = GITHUB_API_BASE + "/repos/{owner}/{repo}/zipball/{branch}";

    private final RestTemplate restTemplate;

    public List<GitHubRepo> listRepos(String accessToken) {
        HttpHeaders headers = bearerHeaders(accessToken);
        ResponseEntity<List<GitHubRepoJson>> response = restTemplate.exchange(
                REPOS_URL,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );
        List<GitHubRepoJson> body = response.getBody();
        if (body == null) return Collections.emptyList();
        return body.stream().map(GitHubRepoJson::toDomain).toList();
    }

    public byte[] downloadZip(String accessToken, String fullName, String defaultBranch) {
        log.debug("Downloading ZIP for {}@{}", fullName, defaultBranch);
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Expected GitHub repo in 'owner/name' form, got: " + fullName);
        }
        HttpHeaders headers = bearerHeaders(accessToken);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                ZIP_URL_TEMPLATE,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class,
                parts[0],            // {owner}
                parts[1],            // {repo}
                defaultBranch         // {branch}
        );
        byte[] body = response.getBody();
        return body != null ? body : new byte[0];
    }

    // ── apply-workflow endpoints (#PR-feature) ───────────────────────────────

    /**
     * Returns the user's permission on a repo as GitHub reports it:
     * "admin" / "maintain" / "write" / "triage" / "read" / "none".
     */
    public RepoPermissionJson getRepoPermission(String accessToken, String fullName) {
        var parts = splitFullName(fullName);
        HttpHeaders headers = bearerHeaders(accessToken);
        ResponseEntity<RepoPermissionJson> response = restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                RepoPermissionJson.class,
                parts[0], parts[1]);
        return response.getBody();
    }

    /** Returns every branch name on the repo, most-recently-pushed first as GitHub orders them. */
    public List<String> listBranches(String accessToken, String fullName) {
        var parts = splitFullName(fullName);
        ResponseEntity<List<BranchJson>> response = restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}/branches?per_page=100",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(accessToken)),
                new ParameterizedTypeReference<>() {},
                parts[0], parts[1]);
        List<BranchJson> body = response.getBody();
        if (body == null) return Collections.emptyList();
        return body.stream().map(BranchJson::name).toList();
    }

    /** Returns the SHA of the branch's head commit, or {@code null} when the branch is absent. */
    public String getBranchHeadSha(String accessToken, String fullName, String branch) {
        var parts = splitFullName(fullName);
        try {
            ResponseEntity<RefJson> response = restTemplate.exchange(
                    GITHUB_API_BASE + "/repos/{owner}/{repo}/git/ref/heads/{branch}",
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(accessToken)),
                    RefJson.class,
                    parts[0], parts[1], branch);
            return response.getBody() != null ? response.getBody().object().sha() : null;
        } catch (HttpClientErrorException.NotFound nf) {
            return null;
        }
    }

    /**
     * Idempotent branch creation.  Returns the SHA of the (existing or new)
     * branch head.  If the branch already exists at a DIFFERENT SHA we
     * throw — callers should ask the user for a different name rather than
     * silently overwrite history.
     */
    public String createBranch(String accessToken, String fullName, String newBranch, String baseSha) {
        var parts = splitFullName(fullName);
        String existingSha = getBranchHeadSha(accessToken, fullName, newBranch);
        if (existingSha != null) {
            if (existingSha.equals(baseSha)) return existingSha;
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Branch '" + newBranch + "' already exists at a different commit (" + existingSha
                    + "); choose another name.");
        }
        HttpHeaders h = bearerHeaders(accessToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<RefJson> response = restTemplate.exchange(
                    GITHUB_API_BASE + "/repos/{owner}/{repo}/git/refs",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("ref", "refs/heads/" + newBranch, "sha", baseSha), h),
                    RefJson.class,
                    parts[0], parts[1]);
            return response.getBody() != null ? response.getBody().object().sha() : baseSha;
        } catch (HttpStatusCodeException e) {
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Could not create branch '" + newBranch + "': " + e.getStatusCode(), e);
        }
    }

    /**
     * Commits a single tree containing all {@code changes} on top of
     * {@code branch}.  One tree, one commit — much cheaper than file-by-file
     * Contents API calls.  Returns the new commit SHA.
     */
    public String commitFilesAsTree(String accessToken, String fullName, String branch,
                                    List<TreeEntry> entries, String message) {
        var parts = splitFullName(fullName);
        HttpHeaders h = bearerHeaders(accessToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        String baseSha = getBranchHeadSha(accessToken, fullName, branch);
        if (baseSha == null) {
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Branch '" + branch + "' does not exist on the remote.");
        }
        // 1) Read the base commit to get its tree.
        ResponseEntity<CommitJson> baseCommit = restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}/git/commits/{sha}",
                HttpMethod.GET, new HttpEntity<>(h), CommitJson.class,
                parts[0], parts[1], baseSha);
        String baseTreeSha = baseCommit.getBody() != null ? baseCommit.getBody().tree().sha() : null;

        // 2) Create blobs for each non-deletion change.
        var treeBuilder = new java.util.ArrayList<Map<String, Object>>();
        for (TreeEntry te : entries) {
            if (te.deletion()) {
                treeBuilder.add(Map.of(
                        "path", te.path(),
                        "mode", "100644",
                        "type", "blob",
                        "sha",  null));     // null sha deletes the entry from the new tree
            } else {
                String blobSha = createBlob(accessToken, parts[0], parts[1], te.contentBase64());
                treeBuilder.add(Map.of(
                        "path", te.path(),
                        "mode", "100644",
                        "type", "blob",
                        "sha",  blobSha));
            }
        }
        // 3) Create a new tree based on baseTreeSha + our patches.
        ResponseEntity<TreeJson> treeResp = restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}/git/trees",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("base_tree", baseTreeSha, "tree", treeBuilder), h),
                TreeJson.class, parts[0], parts[1]);
        String newTreeSha = treeResp.getBody() != null ? treeResp.getBody().sha() : null;

        // 4) Create the commit.
        ResponseEntity<CommitJson> commitResp = restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}/git/commits",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "message", message,
                        "tree",    newTreeSha,
                        "parents", List.of(baseSha)), h),
                CommitJson.class, parts[0], parts[1]);
        String newCommitSha = commitResp.getBody() != null ? commitResp.getBody().sha() : null;

        // 5) Fast-forward the branch ref.
        restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}/git/refs/heads/{branch}",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("sha", newCommitSha, "force", false), h),
                RefJson.class, parts[0], parts[1], branch);
        return newCommitSha;
    }

    /** Creates a blob and returns its SHA. */
    private String createBlob(String token, String owner, String repo, String contentBase64) {
        HttpHeaders h = bearerHeaders(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<BlobJson> resp = restTemplate.exchange(
                GITHUB_API_BASE + "/repos/{owner}/{repo}/git/blobs",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content", contentBase64, "encoding", "base64"), h),
                BlobJson.class, owner, repo);
        return resp.getBody() != null ? resp.getBody().sha() : null;
    }

    /** Opens a Pull Request and returns its number + html url. */
    public PullRequestJson createPullRequest(String accessToken, String fullName,
                                             String head, String base, String title, String body) {
        var parts = splitFullName(fullName);
        HttpHeaders h = bearerHeaders(accessToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<PullRequestJson> resp = restTemplate.exchange(
                    GITHUB_API_BASE + "/repos/{owner}/{repo}/pulls",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "title", title,
                            "head",  head,
                            "base",  base,
                            "body",  body), h),
                    PullRequestJson.class, parts[0], parts[1]);
            return resp.getBody();
        } catch (HttpStatusCodeException e) {
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Could not open PR: " + e.getStatusCode(), e);
        }
    }

    /** Merges {@code head} into {@code base}, returns the merge SHA. */
    public String mergeBranch(String accessToken, String fullName,
                              String head, String base, String message) {
        var parts = splitFullName(fullName);
        HttpHeaders h = bearerHeaders(accessToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<CommitJson> resp = restTemplate.exchange(
                    GITHUB_API_BASE + "/repos/{owner}/{repo}/merges",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "base", base,
                            "head", head,
                            "commit_message", message), h),
                    CommitJson.class, parts[0], parts[1]);
            return resp.getBody() != null ? resp.getBody().sha() : null;
        } catch (HttpStatusCodeException e) {
            HttpStatusCode code = e.getStatusCode();
            if (code == HttpStatus.NO_CONTENT) return null;       // 204 = nothing to merge
            throw new RepositoryProviderPort.RepositoryOperationException(
                    "Merge failed: " + code, e);
        }
    }

    // ── helpers / DTOs ──────────────────────────────────────────────────────

    private static String[] splitFullName(String fullName) {
        if (fullName == null) throw new IllegalArgumentException("fullName is required");
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Expected GitHub repo in 'owner/name' form, got: " + fullName);
        }
        return parts;
    }

    /** Helper for callers that need to encode raw bytes to base64. */
    public static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
    }

    /** Tree-entry shape used by {@link #commitFilesAsTree}. */
    public record TreeEntry(String path, String contentBase64, boolean deletion) {
        public TreeEntry {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepoPermissionJson(
            String name,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("default_branch") String defaultBranch,
            Permissions permissions
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Permissions(boolean admin, boolean maintain, boolean push, boolean triage, boolean pull) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchJson(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefJson(String ref, RefObject object) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record RefObject(String sha, String type, String url) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitJson(String sha, String url, TreeRef tree) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record TreeRef(String sha, String url) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TreeJson(String sha, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlobJson(String sha, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestJson(int number, @JsonProperty("html_url") String htmlUrl, String state) {}

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("Accept", "application/vnd.github+json");
        h.set("X-GitHub-Api-Version", "2022-11-28");
        return h;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubRepoJson(
            long id,
            String name,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("private") boolean privateRepo,
            String description,
            @JsonProperty("html_url") String htmlUrl,
            String language
    ) {
        GitHubRepo toDomain() {
            return new GitHubRepo(id, name, fullName, defaultBranch, privateRepo, description, htmlUrl, language);
        }
    }
}
