package com.altrix.project.domain.service;

import com.altrix.project.domain.model.webhook.NewCommitDetected;
import com.altrix.project.domain.port.out.WebhookEventPublisherPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

/**
 * Parses a GitHub {@code push} webhook payload and — when the auto-trigger
 * feature flag is on — emits a {@link NewCommitDetected} domain event (#90).
 *
 * <p>This service is intentionally infrastructure-agnostic: it takes the raw
 * JSON body bytes and a delivery id, returns an {@code Optional} explaining
 * whether an event was emitted, and depends only on a single out-port for
 * publishing.  ArchUnit-friendly (lives in {@code domain.service}, talks
 * only to domain ports and the standard library).
 *
 * <p>The {@code autoTriggerEnabled} flag is passed in by the caller (read
 * from Spring config in the controller layer) rather than autowired here,
 * keeping the domain layer Spring-free.
 */
@Slf4j
@RequiredArgsConstructor
public class WebhookTriggerService {

    /** Jackson is the only JSON parser already on the classpath via spring-web. */
    private final ObjectMapper objectMapper;
    private final WebhookEventPublisherPort eventPublisher;

    /**
     * @param pushPayloadBody    raw JSON body of a GitHub {@code push} webhook
     * @param deliveryId         X-GitHub-Delivery header — propagated for traceability
     * @param autoTriggerEnabled the {@code webhook.auto-trigger.enabled} flag
     * @return the event emitted, or empty when no event was emitted (flag off,
     *         malformed payload, or missing required fields)
     */
    public Optional<NewCommitDetected> onPush(
            byte[] pushPayloadBody,
            String deliveryId,
            boolean autoTriggerEnabled
    ) {
        if (!autoTriggerEnabled) {
            log.debug("Auto-trigger disabled — skipping push for delivery {}", deliveryId);
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(pushPayloadBody);
        } catch (Exception e) {
            log.warn("Malformed push payload for delivery {}: {}", deliveryId, e.getMessage());
            return Optional.empty();
        }

        String repoUrl   = textOrNull(root, "repository", "clone_url");
        String ref       = textOrNull(root, "ref");                 // "refs/heads/main"
        String commitSha = textOrNull(root, "head_commit", "id");

        if (repoUrl == null || ref == null || commitSha == null) {
            log.warn("Incomplete push payload for delivery {} — missing repoUrl/ref/commitSha", deliveryId);
            return Optional.empty();
        }

        String branch = stripRefsHeads(ref);
        NewCommitDetected event = new NewCommitDetected(
                repoUrl, branch, commitSha, deliveryId, Instant.now());

        eventPublisher.publish(event);
        return Optional.of(event);
    }

    /** Reads a (possibly nested) text field, returning {@code null} when absent. */
    private static String textOrNull(JsonNode root, String... path) {
        JsonNode cursor = root;
        for (String key : path) {
            if (cursor == null || !cursor.has(key)) return null;
            cursor = cursor.get(key);
        }
        return (cursor == null || cursor.isNull()) ? null : cursor.asText();
    }

    /** "refs/heads/main" -> "main"; "refs/tags/v1.0" -> "v1.0"; otherwise unchanged. */
    private static String stripRefsHeads(String ref) {
        if (ref.startsWith("refs/heads/")) return ref.substring("refs/heads/".length());
        if (ref.startsWith("refs/tags/"))  return ref.substring("refs/tags/".length());
        return ref;
    }
}
