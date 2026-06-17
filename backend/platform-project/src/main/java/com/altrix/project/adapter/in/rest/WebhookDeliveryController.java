package com.altrix.project.adapter.in.rest;

import com.altrix.project.domain.model.webhook.WebhookDelivery;
import com.altrix.project.domain.model.webhook.WebhookProcessingStatus;
import com.altrix.project.domain.port.out.WebhookDeliveryRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator-facing endpoints for inspecting and replaying stored webhook
 * deliveries (#92).
 *
 * <p>Distinct from {@link WebhookController} — that one is the GitHub-facing
 * receiver; this one is for humans recovering from transient failures.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/deliveries")
@RequiredArgsConstructor
public class WebhookDeliveryController {

    private final WebhookDeliveryRepositoryPort deliveryRepository;

    /**
     * GET /api/v1/webhooks/deliveries/{deliveryId} — inspect a stored delivery.
     *
     * <p>{@code deliveryId} is the value of the {@code X-GitHub-Delivery} header.
     */
    @GetMapping("/{deliveryId}")
    public ResponseEntity<WebhookDelivery> get(@PathVariable String deliveryId) {
        return deliveryRepository.findByDeliveryId(deliveryId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/webhooks/deliveries/{deliveryId}/replay (#92).
     *
     * <p>Creates a new {@link WebhookDelivery} row carrying the original payload,
     * marked {@code ACCEPTED} and linked back via {@code replay_of}. The
     * verification step is bypassed because the original payload was already
     * trusted (or explicitly rejected — see status rules below).
     *
     * <p>Status rules:
     * <ul>
     *   <li>Original is {@code FAILED} or {@code SKIPPED} → replay allowed (200).</li>
     *   <li>Original is {@code ACCEPTED} → 409 Conflict (already processed once).</li>
     *   <li>No such delivery → 404 Not Found.</li>
     * </ul>
     */
    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<WebhookDelivery> replay(@PathVariable String deliveryId) {
        Optional<WebhookDelivery> original = deliveryRepository.findByDeliveryId(deliveryId);

        if (original.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        WebhookDelivery source = original.get();
        if (source.processingStatus() == WebhookProcessingStatus.ACCEPTED) {
            log.info("Refusing to replay ACCEPTED delivery {}", deliveryId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        Instant now = Instant.now();
        WebhookDelivery replay = new WebhookDelivery(
                UUID.randomUUID().toString(),
                // X-GitHub-Delivery uniqueness across replays — append a suffix.
                source.deliveryId() + "-replay-" + now.toEpochMilli(),
                source.eventType(),
                // Original signature was already verified (or rejected); replay
                // is operator-initiated so we explicitly mark signatureValid=true.
                true,
                WebhookProcessingStatus.ACCEPTED,
                source.payload(),
                null,
                now,
                now,
                source.id()
        );
        WebhookDelivery saved = deliveryRepository.save(replay);
        log.info("Replayed webhook delivery {} -> {}", deliveryId, saved.id());
        return ResponseEntity.ok(saved);
    }
}
