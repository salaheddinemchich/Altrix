package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.IngestProjectRequest;
import com.altrix.orchestrator.adapter.out.github.GitHubApiClient;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Ingests a GitHub repository as a new project.
 *
 * <p>Flow:
 * <ol>
 *   <li>Decrypt the user's stored GitHub token.</li>
 *   <li>Download the repository ZIP from GitHub API.</li>
 *   <li>Forward the ZIP to platform-project's upload endpoint as a multipart form.</li>
 *   <li>Return platform-project's response verbatim.</li>
 * </ol>
 *
 * <p>The multipart body is written by hand using {@link HttpClient} so we have
 * full control over the wire format — Spring's {@code RestTemplate} multipart
 * pipeline mis-set the Content-Type in some setups, yielding 415 from the
 * receiving service.
 *
 * <p>The frontend never sees the raw GitHub token.
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
    private final GitHubApiClient gitHubApiClient;

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
        byte[] zipBytes = gitHubApiClient.downloadZip(accessToken, request.repoFullName(), request.defaultBranch());

        String fileName = request.repoFullName().replace("/", "-") + ".zip";
        String boundary = "----AltrixBoundary" + UUID.randomUUID().toString().replace("-", "");

        byte[] multipartBody = buildMultipartBody(
                boundary,
                fileName,
                zipBytes,
                request.configFormatPreference() != null ? request.configFormatPreference().name() : null
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(platformProjectBaseUrl + "/api/v1/projects/upload"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-User-Id", userId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        log.info("platform-project returned {} for repo '{}' ({} bytes)",
                status, request.repoFullName(), zipBytes.length);

        if (status >= 400) {
            log.error("platform-project rejected upload: {} — body: {}", status, response.body());
        }

        return ResponseEntity.status(HttpStatus.valueOf(status)).body(response.body());
    }

    /**
     * Build a {@code multipart/form-data} body by hand. Each part is preceded
     * by {@code --boundary\r\n}; the terminator is {@code --boundary--\r\n}.
     */
    private static byte[] buildMultipartBody(String boundary,
                                             String filename,
                                             byte[] zipBytes,
                                             String configFormatPreference) throws IOException {
        String delim = "--" + boundary + "\r\n";
        String end   = "--" + boundary + "--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(zipBytes.length + 1024);

        // Part 1: the ZIP file
        out.write(delim.getBytes(StandardCharsets.US_ASCII));
        String fileHeader = "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/zip\r\n\r\n";
        out.write(fileHeader.getBytes(StandardCharsets.US_ASCII));
        out.write(zipBytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));

        // Part 2: the optional configFormatPreference
        if (configFormatPreference != null) {
            out.write(delim.getBytes(StandardCharsets.US_ASCII));
            String prefHeader = "Content-Disposition: form-data; name=\"configFormatPreference\"\r\n\r\n";
            out.write(prefHeader.getBytes(StandardCharsets.US_ASCII));
            out.write(configFormatPreference.getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        out.write(end.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
}
