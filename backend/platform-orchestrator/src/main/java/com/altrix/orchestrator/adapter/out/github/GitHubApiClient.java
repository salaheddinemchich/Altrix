package com.altrix.orchestrator.adapter.out.github;

import com.altrix.orchestrator.domain.model.github.GitHubRepo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

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
