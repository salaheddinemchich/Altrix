package com.altrix.orchestrator.adapter.out.rag;

import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.infrastructure.rag.DomainAllowListValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fallback adapter — fetches documentation via plain HTTPS when MCP is not
 * configured.  When {@code ai.mcp.enabled=true} and an MCP server is wired,
 * {@code mcpDocumentationFetchAdapter} wins and this bean is not registered.
 *
 * <p>Re-validates the URL against the {@link DomainAllowListValidator} as
 * defence in depth: even if {@code DocumentationIngestionService} forgot
 * to check (or someone bypasses it), this adapter refuses to open a
 * socket to an off-list host.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnMissingBean(name = "mcpDocumentationFetchAdapter")
public class HttpDocumentationFetchAdapter implements DocumentationFetchPort {

    private final RestClient http = RestClient.create();
    private final DomainAllowListValidator allowList;

    @Override
    public String fetchPage(String url) {
        if (!allowList.isAllowed(url)) return "";
        log.debug("Fetching documentation via HTTP: {}", url);
        try {
            String raw = http.get().uri(url)
                    .header("User-Agent", "Altrix-RAG-Indexer/1.0")
                    .retrieve()
                    .body(String.class);
            return stripHtml(raw != null ? raw : "");
        } catch (Exception e) {
            log.warn("Failed to fetch documentation from {}: {}", url, e.getMessage());
            return "";
        }
    }

    private String stripHtml(String html) {
        // Remove script/style blocks, then all tags, then normalise whitespace
        return html
                .replaceAll("(?s)<(script|style)[^>]*>.*?</(script|style)>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
