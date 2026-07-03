package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.IngestProjectRequest;
import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Ingests a GitHub repository as a new project (#12, #15 foundation).
 *
 * <p>Flow:
 * <ol>
 *   <li>Decrypt the user's stored GitHub PAT.</li>
 *   <li>POST {@code {repoUrl, branch, accessToken, ...}} to platform-project's
 *       {@code /api/v1/projects/clone} endpoint, which performs a JGit clone,
 *       runs detection, and emits the {@code project.registered} event.</li>
 *   <li>Return platform-project's response verbatim.</li>
 * </ol>
 *
 * <p>Replaces the previous GitHub-API-ZIP-download-and-multipart-forward flow.
 * The frontend never sees the raw GitHub token — it is only used in transit
 * between this controller and platform-project's clone endpoint.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ProjectIngestionController {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final UserRepository userRepository;
    private final ApiKeyEncryptionPort encryption;

    @Value("${platform-project.base-url}")
    private String platformProjectBaseUrl;

    @PostMapping("/from-github")
    public ResponseEntity<String> ingestFromGitHub(
            @Valid @RequestBody IngestProjectRequest request,
            Authentication auth
    ) throws IOException, InterruptedException {
        String userId = auth.getName();
        log.info("Ingesting GitHub repo '{}' for user {}", request.repoFullName(), userId);

        String encryptedToken = userRepository.findEncryptedAccessToken(userId, AuthProviderType.GITHUB)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "No GitHub token found for user — please re-authenticate via GitHub"));

        String accessToken = encryption.decrypt(encryptedToken);
        String repoUrl = "https://github.com/" + request.repoFullName() + ".git";
        String configPref = request.configFormatPreference() != null
                ? "\"" + request.configFormatPreference().name() + "\""
                : "null";
        String jakartaTarget = request.jakartaMessagingTarget() != null
                ? "\"" + request.jakartaMessagingTarget().name() + "\""
                : "null";

        // Build the JSON body for platform-project's /clone endpoint by hand —
        // an out-of-the-box ObjectMapper is overkill for 6 fixed fields and
        // would force the controller to depend on Jackson types directly.
        String jsonBody = String.format(
                "{\"repoUrl\":\"%s\",\"branch\":\"%s\",\"accessToken\":\"%s\",\"shallow\":false,\"configFormatPreference\":%s,\"jakartaMessagingTarget\":%s}",
                jsonEscape(repoUrl),
                jsonEscape(request.defaultBranch()),
                jsonEscape(accessToken),
                configPref,
                jakartaTarget);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(platformProjectBaseUrl + "/api/v1/projects/clone"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("X-User-Id", userId)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        log.info("platform-project /clone returned {} for repo '{}'",
                status, request.repoFullName());

        if (status >= 400) {
            log.error("platform-project rejected clone: {} — body: {}", status, response.body());
        }

        return ResponseEntity.status(HttpStatus.valueOf(status)).body(response.body());
    }

    /**
     * Escape a string so it is safe to embed verbatim between JSON double quotes.
     * Handles every character listed in <a href="https://tools.ietf.org/html/rfc8259#section-7">RFC 8259 §7</a>
     * — backslash, quote, the eight short escapes, and any control byte below 0x20.
     */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
