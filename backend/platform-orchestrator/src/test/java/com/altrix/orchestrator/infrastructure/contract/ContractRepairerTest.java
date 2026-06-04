package com.altrix.orchestrator.infrastructure.contract;

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

/**
 * Behavioural tests for the repair loop.  Combined with {@link ContractValidator}
 * (the real one, not mocked) so we exercise the loop's "validate → patch →
 * re-validate" semantics end-to-end without touching an LLM.
 */
class ContractRepairerTest {

    private AiPort ai;
    private ContractValidator validator;
    private ContractRepairer repairer;

    @BeforeEach
    void setUp() {
        ai = mock(AiPort.class);
        validator = new ContractValidator();
        repairer = new ContractRepairer(validator, ai);
        // Spring would inject these — set them by reflection so the test
        // doesn't need a full ApplicationContext.
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

    @Test
    void patchAppliedWhenAiReturnsCleanReplacement() {
        Map<String, String> files = new LinkedHashMap<>();
        // FILE_CLASS_MISMATCH — public type renamed, file kept.
        files.put("p/Foo.java", "package p;\npublic class Renamed {}");
        // AI returns the corrected version — public type back to Foo.
        when(ai.chat(anyString(), anyString()))
                .thenReturn("package p;\npublic class Foo {}");

        Map<String, String> result = repairer.repair(files);

        assertThat(result.get("p/Foo.java")).contains("class Foo");
        assertThat(validator.isClean(result)).isTrue();
        verify(ai, times(1)).chat(anyString(), anyString());
    }

    @Test
    void loopExitsEarlyWhenNoProgress() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Foo.java", "package p;\npublic class Wrong {}");
        // AI returns the same broken body — no progress.
        when(ai.chat(anyString(), anyString()))
                .thenReturn("package p;\npublic class StillWrong {}");

        Map<String, String> result = repairer.repair(files);

        // Iteration 1: 1 → 1, no improvement → stop.  The repairer accepted
        // the first patch (1 → 1 different content, same violation count),
        // so when it re-validates and sees no improvement it returns.
        // Either way, the AI call count is bounded.
        verify(ai, times(1)).chat(anyString(), anyString());
        // The repair did not produce a clean artifact.
        assertThat(validator.isClean(result)).isFalse();
    }

    @Test
    void brokenAiResponseDoesNotMutateFile() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Foo.java", "package p;\npublic class Wrong {}");
        // Pure prose — no type declaration → repairer rejects the patch.
        when(ai.chat(anyString(), anyString()))
                .thenReturn("Sorry, I cannot fix this file.");

        Map<String, String> result = repairer.repair(files);
        assertThat(result.get("p/Foo.java")).isEqualTo("package p;\npublic class Wrong {}");
    }

    @Test
    void fenceWrappedResponseIsStripped() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Foo.java", "package p;\npublic class Wrong {}");
        when(ai.chat(anyString(), anyString())).thenReturn("""
                ```java
                package p;
                public class Foo {}
                ```
                """);
        Map<String, String> result = repairer.repair(files);
        assertThat(result.get("p/Foo.java")).doesNotContain("```");
        assertThat(result.get("p/Foo.java")).contains("class Foo");
    }

    @Test
    void aiFailureLeavesFileUntouched() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("p/Foo.java", "package p;\npublic class Wrong {}");
        when(ai.chat(anyString(), anyString())).thenThrow(new RuntimeException("rate limit"));

        Map<String, String> result = repairer.repair(files);
        assertThat(result.get("p/Foo.java")).isEqualTo("package p;\npublic class Wrong {}");
    }
}
