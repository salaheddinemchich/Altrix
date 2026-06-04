package com.altrix.orchestrator.adapter.out.project;

import com.altrix.orchestrator.domain.port.out.ProjectMetadataLookupPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * REST-client implementation of {@link ProjectMetadataLookupPort} that
 * talks to platform-project's read endpoint.  Single responsibility —
 * one HTTP call, JSON to domain record translation, that's it.
 *
 * <p>Configurable base URL ({@code platform-project.base-url}); never
 * hardcoded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformProjectMetadataAdapter implements ProjectMetadataLookupPort {

    private final RestTemplate restTemplate;

    @Value("${platform-project.base-url:http://localhost:8082}")
    private String baseUrl;

    @Override
    public Optional<ProjectMetadata> findById(String projectId) {
        if (projectId == null || projectId.isBlank()) return Optional.empty();
        try {
            ProjectJson body = restTemplate.getForObject(
                    baseUrl + "/api/v1/projects/{id}", ProjectJson.class, projectId);
            if (body == null) return Optional.empty();
            return Optional.of(new ProjectMetadata(
                    body.id(),
                    extractRepoFullName(body.repoUrl()),
                    body.trackedBranch(),
                    // Provider id is derived from the repo HOST, NOT the
                    // project.source field — the latter holds enums like
                    // MANUAL / GIT_CLONE / WEBHOOK that say HOW the project
                    // was created, not WHICH platform hosts it.
                    deriveProviderId(body.repoUrl())));
        } catch (HttpClientErrorException e) {
            HttpStatusCode code = e.getStatusCode();
            if (code.value() == 404) return Optional.empty();
            log.warn("platform-project lookup failed for project {}: {}", projectId, code);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("platform-project lookup error for project {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Maps a clone URL host to the provider id our
     * {@link com.altrix.orchestrator.domain.port.out.RepositoryProviderPort}
     * adapters speak.  Case-insensitive substring on the URL so SSH form
     * ({@code git@github.com:owner/repo}) and HTTPS form both match.
     */
    static String deriveProviderId(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
        String lower = repoUrl.toLowerCase();
        if (lower.contains("github.com")) return "github";
        if (lower.contains("gitlab.com") || lower.contains("gitlab.")) return "gitlab";
        if (lower.contains("bitbucket.org") || lower.contains("bitbucket.")) return "bitbucket";
        return null;
    }

    /** Pulls "owner/repo" out of a clone URL like "https://github.com/owner/repo.git". */
    static String extractRepoFullName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
        String stripped = repoUrl.replaceFirst("(?i)\\.git$", "");
        // git@github.com:owner/repo  →  owner/repo
        int colon = stripped.indexOf(':');
        if (colon > 0 && stripped.startsWith("git@")) {
            return stripped.substring(colon + 1);
        }
        // https://host/owner/repo  →  owner/repo
        int doubleSlash = stripped.indexOf("//");
        if (doubleSlash >= 0) {
            String afterScheme = stripped.substring(doubleSlash + 2);
            int firstSlash = afterScheme.indexOf('/');
            if (firstSlash > 0) return afterScheme.substring(firstSlash + 1);
        }
        return stripped;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProjectJson(
            String id,
            @JsonProperty("repoUrl") String repoUrl,
            @JsonProperty("trackedBranch") String trackedBranch,
            @JsonProperty("source") String source
    ) {}
}
