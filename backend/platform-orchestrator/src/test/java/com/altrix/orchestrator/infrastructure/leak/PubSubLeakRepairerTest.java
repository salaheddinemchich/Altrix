package com.altrix.orchestrator.infrastructure.leak;

import com.altrix.orchestrator.domain.port.out.AiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubSubLeakRepairerTest {

    private AiPort ai;
    private PubSubLeakValidator validator;
    private PubSubLeakRepairer repairer;

    @BeforeEach
    void setUp() {
        ai = mock(AiPort.class);
        validator = new PubSubLeakValidator();
        repairer = new PubSubLeakRepairer(validator, ai);
        ReflectionTestUtils.setField(repairer, "maxIterations", 3);
        ReflectionTestUtils.setField(repairer, "maxFileChars", 24000);
    }

    @Test
    void cleanArtifact_returnsUntouchedAndNeverCallsAi() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Foo.java", "package p;\npublic class Foo {}");
        Map<String, String> result = repairer.repair(files);
        assertThat(result).isEqualTo(files);
        verify(ai, never()).chat(anyString(), anyString());
    }

    /**
     * Real failure: PullMessagesTask.java still imports Google Pub/Sub.
     * The AI returns the Kafka rewrite — repairer accepts and validator
     * sees zero remaining leaks.
     */
    @Test
    void leakedPullTask_isPatchedToKafkaConsumer() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/PullMessagesTask.java", """
                package p;
                import com.google.api.services.pubsub.Pubsub;
                import com.google.api.services.pubsub.model.PullRequest;
                public class PullMessagesTask {
                    Pubsub pubsub;
                }
                """);
        when(ai.chat(anyString(), anyString())).thenReturn("""
                package p;
                import org.apache.kafka.clients.consumer.KafkaConsumer;
                public class PullMessagesTask {
                    KafkaConsumer<String,String> consumer;
                }
                """);

        Map<String, String> result = repairer.repair(files);

        assertThat(result.get("p/PullMessagesTask.java"))
                .doesNotContain("com.google.api.services.pubsub")
                .contains("KafkaConsumer");
        assertThat(validator.isClean(result)).isTrue();
        verify(ai, times(1)).chat(anyString(), anyString());
    }

    @Test
    void brokenAiResponse_doesNotMutateFile() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/T.java",
                "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class T {}");
        when(ai.chat(anyString(), anyString())).thenReturn("nope");
        Map<String, String> result = repairer.repair(files);
        assertThat(result.get("p/T.java")).contains("com.google.api.services.pubsub");
    }

    @Test
    void fenceWrappedResponseIsStripped() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/T.java",
                "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class T {}");
        when(ai.chat(anyString(), anyString())).thenReturn("""
                ```java
                package p;
                public class T {}
                ```
                """);
        Map<String, String> result = repairer.repair(files);
        assertThat(result.get("p/T.java")).doesNotContain("```");
        assertThat(result.get("p/T.java")).doesNotContain("com.google.api.services.pubsub");
    }

    @Test
    void aiFailureLeavesFileUntouched() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/T.java",
                "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class T {}");
        when(ai.chat(anyString(), anyString())).thenThrow(new RuntimeException("rate limit"));
        Map<String, String> result = repairer.repair(files);
        assertThat(result.get("p/T.java")).contains("com.google.api.services.pubsub");
    }

    @Test
    void loopExitsWhenNoProgress() {
        // AI returns content with the same leak count — no improvement → stop.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/T.java",
                "package p;\nimport com.google.api.services.pubsub.Pubsub;\npublic class T {}");
        when(ai.chat(anyString(), anyString())).thenReturn(
                "package p;\nimport com.google.api.services.pubsub.PullRequest;\npublic class T {}");
        Map<String, String> result = repairer.repair(files);
        verify(ai, times(1)).chat(anyString(), anyString());
        assertThat(validator.isClean(result)).isFalse();
    }
}
