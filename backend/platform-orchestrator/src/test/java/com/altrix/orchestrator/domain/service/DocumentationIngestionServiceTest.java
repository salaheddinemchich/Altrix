package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.DocumentChunk;
import com.altrix.orchestrator.domain.port.out.DocumentationFetchPort;
import com.altrix.orchestrator.domain.port.out.EmbeddingStorePort;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig.AllowedDomain;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig.Page;
import com.altrix.orchestrator.infrastructure.rag.DomainAllowListValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DocumentationIngestionServiceTest {

    private DocumentationCorpusConfig corpus(List<AllowedDomain> domains, List<Page> pages) {
        return new DocumentationCorpusConfig(domains, pages);
    }

    @Test
    void emptyCorpusIsSafelyHandled() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        var c = corpus(List.of(), List.of());
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        verifyNoInteractions(store);
        verifyNoInteractions(fetch);
    }

    @Test
    void disallowedUrlsAreSkippedBeforeAnyNetworkCall() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        // Allow-list only kafka.apache.org; page references medium.com instead.
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(new Page("evil/medium", "https://medium.com/some-article")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        verify(fetch, never()).fetchPage(anyString());
        verify(store, never()).upsert(any());
    }

    @Test
    void existingDocsAreSkipped() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.documentationExists("kafka/producers")).thenReturn(true);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(new Page("kafka/producers", "https://kafka.apache.org/documentation/#producerapi")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        verify(store).documentationExists("kafka/producers");
        verify(fetch, never()).fetchPage(anyString());
        verify(store, never()).upsert(any());
    }

    @Test
    void allowedAndNewPageIsFetchedAndUpserted() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.documentationExists("kafka/producers")).thenReturn(false);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        when(fetch.fetchPage(anyString())).thenReturn(
                "Kafka producers send records to topics.\n\nProducers are thread-safe.");
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(new Page("kafka/producers", "https://kafka.apache.org/documentation/#producerapi")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        verify(fetch).fetchPage("https://kafka.apache.org/documentation/#producerapi");
        // Avoid ArgumentCaptor + Class<List<DocumentChunk>> raw-type cast —
        // argThat with a typed predicate is the cleaner equivalent.
        verify(store).upsert(argThat((List<DocumentChunk> chunks) ->
                chunks != null
                        && !chunks.isEmpty()
                        && "kafka/producers".equals(chunks.get(0).filePath())
                        && "https://kafka.apache.org/documentation/#producerapi"
                                .equals(chunks.get(0).sourceUrl())));
    }

    @Test
    void emptyFetchBodyIsCountedAsFailedNotIngested() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.documentationExists(anyString())).thenReturn(false);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        when(fetch.fetchPage(anyString())).thenReturn("");
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(new Page("kafka/producers", "https://kafka.apache.org/documentation/#producerapi")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        verify(fetch).fetchPage(anyString());
        verify(store, never()).upsert(any());
    }

    @Test
    void prunesStaleDocsBeforeIngesting() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.documentationExists(anyString())).thenReturn(false);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        when(fetch.fetchPage(anyString())).thenReturn("Some content.\n\nMore.");
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(
                        new Page("kafka/producers", "https://kafka.apache.org/documentation/#producerapi"),
                        new Page("kafka/consumers", "https://kafka.apache.org/documentation/#consumerapi")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        // Prune should be invoked with EXACTLY the current corpus's logical
        // paths.  Any stale row (e.g. the removed migration/kafka-to-pubsub
        // entry) gets deleted before the new ingest starts.
        verify(store).deleteDocumentationNotIn(argThat(set ->
                set.size() == 2
                        && set.contains("kafka/producers")
                        && set.contains("kafka/consumers")));
    }

    @Test
    void prunesEvenWhenEveryPageWouldOtherwiseSkipAsExisting() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.documentationExists(anyString())).thenReturn(true);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(new Page("kafka/producers", "https://kafka.apache.org/documentation/#producerapi")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        // Prune runs BEFORE the existence check, so even a no-op ingest
        // still cleans up stale rows from a previous corpus.
        verify(store).deleteDocumentationNotIn(argThat(set ->
                set.size() == 1 && set.contains("kafka/producers")));
        verify(fetch, never()).fetchPage(anyString());
    }

    @Test
    void pruneFailureDoesNotAbortIngest() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.deleteDocumentationNotIn(anyCollection()))
                .thenThrow(new RuntimeException("db down"));
        when(store.documentationExists(anyString())).thenReturn(false);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        when(fetch.fetchPage(anyString())).thenReturn("Content.\n\nMore.");
        var c = corpus(
                List.of(new AllowedDomain("kafka.apache.org", List.of())),
                List.of(new Page("kafka/producers", "https://kafka.apache.org/documentation/#producerapi")));
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        // Must not throw — ingest carries on.
        svc.ingestOnStartup();

        verify(fetch).fetchPage("https://kafka.apache.org/documentation/#producerapi");
        verify(store).upsert(any());
    }

    @Test
    void perPageExceptionDoesNotAbortTheRunForOtherPages() {
        EmbeddingStorePort store = mock(EmbeddingStorePort.class);
        when(store.documentationExists(anyString())).thenReturn(false);
        DocumentationFetchPort fetch = mock(DocumentationFetchPort.class);
        when(fetch.fetchPage("https://kafka.apache.org/a")).thenThrow(new RuntimeException("network down"));
        when(fetch.fetchPage("https://kafka.apache.org/b")).thenReturn("Working page content.\n\nMore content.");

        var pages = new ArrayList<Page>();
        pages.add(new Page("kafka/a", "https://kafka.apache.org/a"));
        pages.add(new Page("kafka/b", "https://kafka.apache.org/b"));
        var c = corpus(List.of(new AllowedDomain("kafka.apache.org", List.of())), pages);
        var svc = new DocumentationIngestionService(store, fetch, new DomainAllowListValidator(c), c);

        svc.ingestOnStartup();

        // First page threw — store.upsert should still have been called for page B.
        verify(store, times(1)).upsert(any());
    }
}
