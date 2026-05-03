package com.migrator.common.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResultTest {

    @Test
    void success_carriesValueAndIsSuccess() {
        AgentResult<String> r = AgentResult.success("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r).isInstanceOf(AgentResult.Success.class);
        assertThat(((AgentResult.Success<String>) r).value()).isEqualTo("hello");
    }

    @Test
    void failure_carriesReasonAndIsNotSuccess() {
        AgentResult<String> r = AgentResult.failure("boom");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r).isInstanceOf(AgentResult.Failure.class);
        assertThat(((AgentResult.Failure<String>) r).reason()).isEqualTo("boom");
    }

    @Test
    void sealed_onlyTwoPermittedSubtypes() {
        Class<?>[] permitted = AgentResult.class.getPermittedSubclasses();
        assertThat(permitted).containsExactlyInAnyOrder(
                AgentResult.Success.class, AgentResult.Failure.class);
    }

    @Test
    void canPatternMatchExhaustively() {
        AgentResult<Integer> ok  = AgentResult.success(42);
        AgentResult<Integer> err = AgentResult.failure("nope");

        String okStr  = describe(ok);
        String errStr = describe(err);

        assertThat(okStr).isEqualTo("ok=42");
        assertThat(errStr).isEqualTo("err=nope");
    }

    private static String describe(AgentResult<Integer> r) {
        return switch (r) {
            case AgentResult.Success<Integer> s -> "ok=" + s.value();
            case AgentResult.Failure<Integer> f -> "err=" + f.reason();
        };
    }
}
