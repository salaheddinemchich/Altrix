package com.altrix.project.adapter.in.rest;

import com.altrix.project.domain.model.webhook.WebhookDelivery;
import com.altrix.project.domain.model.webhook.WebhookProcessingStatus;
import com.altrix.project.domain.port.out.WebhookDeliveryRepositoryPort;
import com.altrix.project.infrastructure.security.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class WebhookControllerTest {

    private WebhookSignatureVerifier verifier;
    private WebhookDeliveryRepositoryPort repository;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(WebhookSignatureVerifier.class);
        repository = mock(WebhookDeliveryRepositoryPort.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new WebhookController(verifier, repository);
    }

    @Test
    void valid_push_event_returns_200_and_persists_as_ACCEPTED() {
        byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(body, "sha256=abc")).thenReturn(true);

        var response = controller.receiveGitHub("push", "del-1", "sha256=abc", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        WebhookDelivery saved = captureSaved();
        assertThat(saved.processingStatus()).isEqualTo(WebhookProcessingStatus.ACCEPTED);
        assertThat(saved.signatureValid()).isTrue();
        assertThat(saved.deliveryId()).isEqualTo("del-1");
        assertThat(saved.eventType()).isEqualTo("push");
        assertThat(saved.processedAt()).isNotNull();
    }

    @Test
    void invalid_signature_returns_401_and_persists_as_FAILED() {
        byte[] body = "{\"forged\":true}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(false);

        var response = controller.receiveGitHub("push", "del-2", "sha256=bad", body);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        WebhookDelivery saved = captureSaved();
        assertThat(saved.processingStatus()).isEqualTo(WebhookProcessingStatus.FAILED);
        assertThat(saved.signatureValid()).isFalse();
        assertThat(saved.errorMessage()).contains("Invalid or missing X-Hub-Signature-256");
    }

    @Test
    void missing_signature_header_returns_401() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(eq(body), isNull())).thenReturn(false);

        var response = controller.receiveGitHub("push", "del-3", null, body);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void unknown_event_type_is_persisted_as_SKIPPED_with_200() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);

        var response = controller.receiveGitHub("repository_dispatch", "del-4", "sha256=ok", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        WebhookDelivery saved = captureSaved();
        assertThat(saved.processingStatus()).isEqualTo(WebhookProcessingStatus.SKIPPED);
        assertThat(saved.errorMessage()).contains("not supported");
    }

    @Test
    void ping_event_is_supported_and_returns_200() {
        byte[] body = "{\"zen\":\"Speak like a human.\"}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);

        var response = controller.receiveGitHub("ping", "del-5", "sha256=ok", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        WebhookDelivery saved = captureSaved();
        assertThat(saved.processingStatus()).isEqualTo(WebhookProcessingStatus.ACCEPTED);
    }

    @Test
    void persistence_failure_does_not_break_the_response() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        var response = controller.receiveGitHub("push", "del-6", "sha256=ok", body);

        // GitHub still sees 200 — audit-log failures must not retry-storm us
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private WebhookDelivery captureSaved() {
        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(repository).save(cap.capture());
        return cap.getValue();
    }
}
