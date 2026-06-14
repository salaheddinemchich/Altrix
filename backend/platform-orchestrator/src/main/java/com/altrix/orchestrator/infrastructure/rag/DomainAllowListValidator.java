package com.altrix.orchestrator.infrastructure.rag;

import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Stateless gate that decides whether a URL is allowed to be fetched by
 * the documentation ingester.  Compares the URL's host (and optional path
 * prefix) against {@link DocumentationCorpusConfig#allowedDomains()}.
 *
 * <p>Wired into both fetch adapters as defence in depth:
 * <ul>
 *   <li>{@code McpDocumentationFetchAdapter} — refuses to call the MCP
 *       {@code fetch} tool with an off-list URL even if config drifted.</li>
 *   <li>{@code HttpDocumentationFetchAdapter} — refuses to open the
 *       socket so a misconfigured fallback can't escape the policy.</li>
 * </ul>
 *
 * <p>Hosts are matched case-insensitively against the configured host.
 * Path prefixes are matched literally on the URL's path component (case
 * sensitive — Google's URLs are).  An empty path-prefix list means the
 * whole host is allowed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainAllowListValidator {

    private final DocumentationCorpusConfig config;

    /**
     * @return true if {@code url} is parseable AND matches at least one
     *         entry in the allow-list.  Logs WARN and returns false for
     *         malformed URLs.
     */
    public boolean isAllowed(String url) {
        if (url == null || url.isBlank()) return false;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            log.warn("Rejecting unparseable documentation URL: {}", url);
            return false;
        }
        String host = uri.getHost();
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (host == null || host.isBlank()) {
            log.warn("Rejecting documentation URL with no host: {}", url);
            return false;
        }
        String hostLower = host.toLowerCase();

        for (DocumentationCorpusConfig.AllowedDomain allowed : config.allowedDomains()) {
            if (!allowed.host().equals(hostLower)) continue;
            if (allowed.pathPrefixes().isEmpty()) return true;
            for (String prefix : allowed.pathPrefixes()) {
                if (path.startsWith(prefix)) return true;
            }
            // Host matched but no path prefix matched — fall through and
            // try the next allow entry (in case the same host is listed
            // twice with different prefix sets).
        }

        log.warn("Documentation URL rejected by allow-list: {}", url);
        return false;
    }
}
