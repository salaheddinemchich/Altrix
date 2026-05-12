package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.GitHubRepoResponse;
import com.altrix.orchestrator.adapter.out.github.GitHubApiClient;
import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Lists GitHub repositories accessible to the authenticated user.
 * Uses the user's stored OAuth token (decrypted at request time) to call
 * the GitHub API — no token is ever returned to the frontend.
 */
@RestController
@RequestMapping("/api/v1/repositories")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RepositoryController {

    private final UserRepository userRepository;
    private final ApiKeyEncryptionPort encryption;
    private final GitHubApiClient gitHubApiClient;

    @GetMapping
    public ResponseEntity<List<GitHubRepoResponse>> listRepositories(Authentication auth) {
        String userId = auth.getName();
        String encryptedToken = userRepository.findEncryptedAccessToken(userId, AuthProviderType.GITHUB)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "No GitHub token found for user — please re-authenticate via GitHub"));

        String accessToken = encryption.decrypt(encryptedToken);
        List<GitHubRepoResponse> repos = gitHubApiClient.listRepos(accessToken)
                .stream()
                .map(GitHubRepoResponse::from)
                .toList();

        return ResponseEntity.ok(repos);
    }
}
