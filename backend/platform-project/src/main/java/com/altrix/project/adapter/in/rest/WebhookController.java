package com.altrix.project.adapter.in.rest;

import com.altrix.project.domain.model.webhook.WebhookDelivery;
import com.altrix.project.domain.model.webhook.WebhookProcessingStatus;
import com.altrix.project.domain.port.out.WebhookDeliveryRepositoryPort;
import com.altrix.project.infrastructure.security.WebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Receives GitHub webhook POSTs (#89) and persists every delivery for audit (#91).
 *
 * <p>Order of operations matters for security:
 * <ol>
 *   <li>Read the request body as raw bytes — Spring's auto-deserialised DTO
 *       would re-serialise to a slightly different string, breaking the HMAC.</li>
 *   <li>Verify the HMAC-SHA256 signature.</li>
 *   <li>Persist the delivery row (regardless of signature outcome) so we
 *       have a complete audit trail.</li>
 *   <li>Return 401 to GitHub when invalid, 200 otherwise.</li>
 * </ol>
 *
 * <p>GitHub requires a response within 10 seconds, so this controller only
 * verifies + persists.  Actual re-migration triggering happens asynchronously
 * in {@code WebhookTriggerService} (#90, follow-up).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    /** Events we recognise; anything else lands in the audit log as SKIPPED. */
    private static final Set<String> SUPPORTED_EVENTS = Set.of("push", "pull_request", "ping");

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookDeliveryRepositoryPort deliveryRepository;

    @PostMapping(value = "/github", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receiveGitHub(
            @RequestHeader(value = "X-GitHub-Event",      required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery",   required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody byte[] rawBody
    ) {
        boolean valid = signatureVerifier.verify(rawBody, signatureHeader);

        WebhookProcessingStatus status;
        String errorMessage = null;
        if (!valid) {
            status = WebhookProcessingStatus.FAILED;
            errorMessage = "Invalid or missing X-Hub-Signature-256";
        } else if (eventType == null || !SUPPORTED_EVENTS.contains(eventType)) {
            status = WebhookProcessingStatus.SKIPPED;
            errorMessage = "Event type not supported: " + eventType;
        } else {
            // Downstream re-migration triggering will be wired in #90; for now
            // we accept and record the delivery so GitHub stops retrying.
            status = WebhookProcessingStatus.ACCEPTED;
        }

        persistDelivery(deliveryId, eventType, valid, status, errorMessage, rawBody);

        if (!valid) {
            log.warn("Rejected webhook delivery {} — bad signature", deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }

        log.info("Webhook {} ({}): {}", deliveryId, eventType, status);
        return ResponseEntity.ok(status.name());
    }

    private void persistDelivery(
            String deliveryId,
            String eventType,
            boolean signatureValid,
            WebhookProcessingStatus status,
            String errorMessage,
            byte[] rawBody
    ) {
        try {
            WebhookDelivery delivery = new WebhookDelivery(
                    UUID.randomUUID().toString(),
                    deliveryId != null ? deliveryId : "unknown",
                    eventType != null ? eventType : "unknown",
                    signatureValid,
                    status,
                    new String(rawBody, StandardCharsets.UTF_8),
                    errorMessage,
                    Instant.now(),
                    status == WebhookProcessingStatus.ACCEPTED ? Instant.now() : null,
                    null  // replayOf — first-time delivery, not a replay
            );
            deliveryRepository.save(delivery);
        } catch (Exception e) {
            // Audit-log persistence must never block the response — log and move on.
            log.error("Failed to persist webhook delivery {}: {}", deliveryId, e.getMessage());
        }
    }
}
