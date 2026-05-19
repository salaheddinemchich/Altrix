package com.altrix.project.domain.service;

import com.altrix.project.domain.model.webhook.NewCommitDetected;
import com.altrix.project.domain.port.out.WebhookEventPublisherPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebhookTriggerServiceTest {

    private static final String VALID_PUSH = """
            {
              "ref": "refs/heads/main",
              "head_commit": { "id": "0123456789012345678901234567890123456789" },
              "repository": {
                "full_name": "acme/widgets",
                "clone_url": "https://github.com/acme/widgets.git",
                "default_branch": "main"
              }
            }
            """;

    private WebhookEventPublisherPort publisher;
    private WebhookTriggerService service;

    @BeforeEach
    void setUp() {
        publisher = mock(WebhookEventPublisherPort.class);
        service = new WebhookTriggerService(new ObjectMapper(), publisher);
    }

    @Test
    void emits_event_when_feature_flag_is_on_and_payload_is_valid() {
        Optional<NewCommitDetected> result = service.onPush(
                VALID_PUSH.getBytes(StandardCharsets.UTF_8), "del-1", true);

        assertThat(result).isPresent();
        NewCommitDetected event = captureEvent();
        assertThat(event.repoUrl()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(event.branch()).isEqualTo("main");
        assertThat(event.commitSha()).hasSize(40);
        assertThat(event.deliveryId()).isEqualTo("del-1");
    }

    @Test
    void does_nothing_when_feature_flag_is_off() {
        Optional<NewCommitDetected> result = service.onPush(
                VALID_PUSH.getBytes(StandardCharsets.UTF_8), "del-1", false);

        assertThat(result).isEmpty();
        verify(publisher, never()).publish(any());
    }

    @Test
    void strips_refs_heads_prefix_from_branch_name() {
        Optional<NewCommitDetected> result = service.onPush(
                VALID_PUSH.replace("refs/heads/main", "refs/heads/feature/abc")
                          .getBytes(StandardCharsets.UTF_8),
                "del-2", true);

        assertThat(result).isPresent();
        assertThat(result.get().branch()).isEqualTo("feature/abc");
    }

    @Test
    void strips_refs_tags_prefix_for_tag_pushes() {
        Optional<NewCommitDetected> result = service.onPush(
                VALID_PUSH.replace("refs/heads/main", "refs/tags/v1.0.0")
                          .getBytes(StandardCharsets.UTF_8),
                "del-3", true);

        assertThat(result).isPresent();
        assertThat(result.get().branch()).isEqualTo("v1.0.0");
    }

    @Test
    void returns_empty_when_payload_is_not_valid_json() {
        Optional<NewCommitDetected> result = service.onPush(
                "not-json".getBytes(StandardCharsets.UTF_8), "del-4", true);

        assertThat(result).isEmpty();
        verify(publisher, never()).publish(any());
    }

    @Test
    void returns_empty_when_clone_url_is_missing() {
        String withoutCloneUrl = """
                {
                  "ref": "refs/heads/main",
                  "head_commit": { "id": "0123456789012345678901234567890123456789" },
                  "repository": { "full_name": "acme/widgets" }
                }
                """;
        Optional<NewCommitDetected> result = service.onPush(
                withoutCloneUrl.getBytes(StandardCharsets.UTF_8), "del-5", true);

        assertThat(result).isEmpty();
        verify(publisher, never()).publish(any());
    }

    @Test
    void returns_empty_when_head_commit_id_is_missing() {
        String withoutSha = """
                {
                  "ref": "refs/heads/main",
                  "head_commit": {},
                  "repository": { "clone_url": "https://github.com/x/y.git" }
                }
                """;
        Optional<NewCommitDetected> result = service.onPush(
                withoutSha.getBytes(StandardCharsets.UTF_8), "del-6", true);

        assertThat(result).isEmpty();
    }

    @Test
    void returns_empty_when_ref_is_missing() {
        String withoutRef = """
                {
                  "head_commit": { "id": "0123456789012345678901234567890123456789" },
                  "repository": { "clone_url": "https://github.com/x/y.git" }
                }
                """;
        Optional<NewCommitDetected> result = service.onPush(
                withoutRef.getBytes(StandardCharsets.UTF_8), "del-7", true);

        assertThat(result).isEmpty();
    }

    @Test
    void event_detectedAt_is_populated_to_a_recent_instant() {
        long before = System.currentTimeMillis();
        Optional<NewCommitDetected> result = service.onPush(
                VALID_PUSH.getBytes(StandardCharsets.UTF_8), "del-8", true);
        long after = System.currentTimeMillis();

        assertThat(result).isPresent();
        long detected = result.get().detectedAt().toEpochMilli();
        assertThat(detected).isBetween(before, after);
    }

    private NewCommitDetected captureEvent() {
        ArgumentCaptor<NewCommitDetected> cap = ArgumentCaptor.forClass(NewCommitDetected.class);
        verify(publisher).publish(cap.capture());
        return cap.getValue();
    }
}
