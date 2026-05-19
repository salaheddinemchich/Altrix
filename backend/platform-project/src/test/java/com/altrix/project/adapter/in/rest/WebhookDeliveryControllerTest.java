package com.altrix.project.adapter.in.rest;

import com.altrix.project.domain.model.webhook.WebhookDelivery;
import com.altrix.project.domain.model.webhook.WebhookProcessingStatus;
import com.altrix.project.domain.port.out.WebhookDeliveryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookDeliveryControllerTest {

    private WebhookDeliveryRepositoryPort repository;
    private WebhookDeliveryController controller;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookDeliveryRepositoryPort.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new WebhookDeliveryController(repository);
    }

    // ── GET /{deliveryId} ────────────────────────────────────────────────────

    @Test
    void get_returns_200_when_delivery_exists() {
        WebhookDelivery d = sample("del-1", WebhookProcessingStatus.FAILED);
        when(repository.findByDeliveryId("del-1")).thenReturn(Optional.of(d));

        var response = controller.get("del-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().deliveryId()).isEqualTo("del-1");
    }

    @Test
    void get_returns_404_when_delivery_absent() {
        when(repository.findByDeliveryId("missing")).thenReturn(Optional.empty());

        var response = controller.get("missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    // ── POST /{deliveryId}/replay ────────────────────────────────────────────

    @Test
    void replay_creates_new_ACCEPTED_row_for_FAILED_original() {
        WebhookDelivery original = sample("del-orig", WebhookProcessingStatus.FAILED);
        when(repository.findByDeliveryId("del-orig")).thenReturn(Optional.of(original));

        var response = controller.replay("del-orig");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        WebhookDelivery saved = captureSaved();
        assertThat(saved.processingStatus()).isEqualTo(WebhookProcessingStatus.ACCEPTED);
        assertThat(saved.signatureValid()).isTrue();
        assertThat(saved.replayOf()).isEqualTo(original.id());
        assertThat(saved.payload()).isEqualTo(original.payload());
        assertThat(saved.deliveryId()).startsWith("del-orig-replay-");
        assertThat(saved.processedAt()).isNotNull();
    }

    @Test
    void replay_allowed_for_SKIPPED_original() {
        WebhookDelivery original = sample("del-skip", WebhookProcessingStatus.SKIPPED);
        when(repository.findByDeliveryId("del-skip")).thenReturn(Optional.of(original));

        var response = controller.replay("del-skip");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(repository).save(any());
    }

    @Test
    void replay_returns_409_when_original_is_ACCEPTED() {
        WebhookDelivery original = sample("del-ok", WebhookProcessingStatus.ACCEPTED);
        when(repository.findByDeliveryId("del-ok")).thenReturn(Optional.of(original));

        var response = controller.replay("del-ok");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        // No new row written
        verify(repository, never()).save(any());
    }

    @Test
    void replay_returns_404_when_delivery_does_not_exist() {
        when(repository.findByDeliveryId("missing")).thenReturn(Optional.empty());

        var response = controller.replay("missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(repository, never()).save(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static WebhookDelivery sample(String deliveryId, WebhookProcessingStatus status) {
        return new WebhookDelivery(
                "uuid-" + deliveryId,
                deliveryId,
                "push",
                status != WebhookProcessingStatus.FAILED,
                status,
                "{\"ref\":\"refs/heads/main\"}",
                status == WebhookProcessingStatus.FAILED ? "bad signature" : null,
                Instant.parse("2026-05-01T10:00:00Z"),
                status == WebhookProcessingStatus.ACCEPTED ? Instant.parse("2026-05-01T10:00:01Z") : null,
                null
        );
    }

    private WebhookDelivery captureSaved() {
        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(repository).save(cap.capture());
        return cap.getValue();
    }
}
