package com.altrix.orchestrator.adapter.out.rag;

import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fallback adapter — fetches documentation via plain HTTP GET when MCP is not enabled.
 * Returns raw text; HTML tags are stripped so the content is clean for embedding.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "mcpDocumentationFetchAdapter")
public class HttpDocumentationFetchAdapter implements DocumentationFetchPort {

    private final RestClient http = RestClient.create();

    @Override
    public String fetchPage(String url) {
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
