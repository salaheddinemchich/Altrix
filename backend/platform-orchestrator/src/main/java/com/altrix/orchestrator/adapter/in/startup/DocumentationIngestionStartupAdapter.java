package com.altrix.orchestrator.adapter.in.startup;

import com.altrix.orchestrator.domain.service.DocumentationIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Inbound adapter — triggers documentation ingestion after Spring context starts.
 * Delegates entirely to the framework-free domain service.
 */
@Component
@RequiredArgsConstructor
public class DocumentationIngestionStartupAdapter {

    private final DocumentationIngestionService ingestionService;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ingestionService.ingestOnStartup();
    }
}
