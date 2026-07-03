package com.altrix.orchestrator.adapter.out.rag;

import com.altrix.orchestrator.infrastructure.ai.tools.McpToolsPort;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig.AllowedDomain;
import com.altrix.orchestrator.infrastructure.rag.DomainAllowListValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class McpDocumentationFetchAdapterTest {

    private McpToolsPort mcpTools;
    private DocumentationCorpusConfig corpus;
    private McpDocumentationFetchAdapter adapter;

    @BeforeEach
    void setUp() {
        mcpTools = mock(McpToolsPort.class);
        corpus = new DocumentationCorpusConfig(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of());
        var validator = new DomainAllowListValidator(corpus);
        adapter = new McpDocumentationFetchAdapter(mcpTools, new ObjectMapper(), validator);
        // The @Value field doesn't get populated outside Spring; inject the
        // production default so the test exercises the real candidate list.
        ReflectionTestUtils.setField(adapter, "fetchToolCandidates",
                "fetch,web_fetch,http_fetch,url_fetch");
    }

    @Test
    void disallowedUrlIsRefusedBeforeAnyMcpCall() {
        String body = adapter.fetchPage("https://medium.com/some-article");
        assertThat(body).isEmpty();
        verifyNoInteractions(mcpTools);
    }

    @Test
    void returnsEmptyWhenNoFetchToolIsRegistered() {
        when(mcpTools.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("filesystem_read").description("nope").build()
        ));

        String body = adapter.fetchPage("https://kafka.apache.org/documentation/#producerapi");

        assertThat(body).isEmpty();
        verify(mcpTools, never()).executeTool(anyString(), anyString());
    }

    @Test
    void picksFirstMatchingFetchTool() {
        // Only `web_fetch` exists; `fetch` does not.
        when(mcpTools.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("web_fetch").description("fetch a url").build()
        ));
        when(mcpTools.executeTool(eq("web_fetch"), anyString()))
                .thenReturn("<html><body><p>Kafka producers</p></body></html>");

        String body = adapter.fetchPage("https://kafka.apache.org/documentation/#producerapi");

        assertThat(body).contains("Kafka producers");
        // HTML was stripped — no tags should leak through.
        assertThat(body).doesNotContain("<");
        verify(mcpTools).executeTool(eq("web_fetch"),
                contains("\"url\":\"https://kafka.apache.org/documentation/#producerapi\""));
    }

    @Test
    void preferenceOrderHonoursCsvWhenMultipleToolsAreAvailable() {
        // Both `fetch` and `web_fetch` exist — `fetch` is listed first in the CSV, so it wins.
        when(mcpTools.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("web_fetch").description("alt").build(),
                ToolSpecification.builder().name("fetch").description("preferred").build()
        ));
        when(mcpTools.executeTool(eq("fetch"), anyString())).thenReturn("Some content paragraph.");

        String body = adapter.fetchPage("https://kafka.apache.org/documentation/#producerapi");

        assertThat(body).isEqualTo("Some content paragraph.");
        verify(mcpTools).executeTool(eq("fetch"), anyString());
        verify(mcpTools, never()).executeTool(eq("web_fetch"), anyString());
    }

    @Test
    void emptyMcpResponseProducesEmptyOutput() {
        when(mcpTools.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("fetch").build()
        ));
        when(mcpTools.executeTool(anyString(), anyString())).thenReturn("");

        String body = adapter.fetchPage("https://kafka.apache.org/documentation/#producerapi");

        assertThat(body).isEmpty();
    }
}
