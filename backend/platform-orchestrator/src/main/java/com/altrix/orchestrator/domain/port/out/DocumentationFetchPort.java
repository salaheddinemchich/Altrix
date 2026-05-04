package com.altrix.orchestrator.domain.port.out;

/**
 * Driven port — fetches raw documentation text from an external source.
 * Implemented by the MCP adapter (fetches live docs via MCP tool calls)
 * with an HTTP fallback adapter.
 */
public interface DocumentationFetchPort {

    /**
     * Fetches the text content of a documentation page.
     *
     * @param url canonical URL of the documentation page
     * @return plain text content (stripped of HTML/Markdown formatting)
     */
    String fetchPage(String url);
}
