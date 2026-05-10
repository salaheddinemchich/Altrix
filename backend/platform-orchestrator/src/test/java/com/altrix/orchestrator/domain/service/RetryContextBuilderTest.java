package com.altrix.orchestrator.domain.service;

import com.altrix.common.domain.model.ValidationReport;
import com.altrix.orchestrator.infrastructure.ai.RetryContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RetryContextBuilderTest {

    private RetryContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new RetryContextBuilder();
    }

    @Test
    void returns_empty_string_when_report_is_null() {
        assertThat(builder.build(null)).isEmpty();
    }

    @Test
    void returns_empty_string_when_report_passed() {
        ValidationReport passed = new ValidationReport("p1", true, List.of(), "all good");
        assertThat(builder.build(passed)).isEmpty();
    }

    @Test
    void returns_empty_string_when_no_failures() {
        ValidationReport noFailures = new ValidationReport("p1", false, List.of(), "odd");
        assertThat(builder.build(noFailures)).isEmpty();
    }

    @Test
    void includes_header_and_failure_list_for_single_failure() {
        ValidationReport report = new ValidationReport("p1", false,
                List.of("Foo.java: Pub/Sub import not removed"), "1 issue");

        String result = builder.build(report);

        assertThat(result).startsWith(RetryContextBuilder.HEADER);
        assertThat(result).contains("Pub/Sub import not removed");
        assertThat(result).contains("1.");
    }

    @Test
    void includes_all_failures_when_within_budget_and_max_count() {
        List<String> failures = List.of(
                "A.java: issue one",
                "B.java: issue two",
                "C.java: issue three"
        );
        ValidationReport report = new ValidationReport("p1", false, failures, "3 issues");

        String result = builder.build(report);

        assertThat(result).contains("A.java: issue one");
        assertThat(result).contains("B.java: issue two");
        assertThat(result).contains("C.java: issue three");
        assertThat(result).doesNotContain("more issue");
    }

    @Test
    void caps_at_max_failures_and_appends_omitted_count() {
        List<String> failures = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "File" + i + ".java: issue " + i)
                .toList();
        ValidationReport report = new ValidationReport("p1", false, failures, "10 issues");

        String result = builder.build(report);

        // At most MAX_FAILURES entries included
        long entryCount = result.lines()
                .filter(line -> line.matches("^\\d+\\..*"))
                .count();
        assertThat(entryCount).isLessThanOrEqualTo(RetryContextBuilder.MAX_FAILURES);
        assertThat(result).contains("more issue");
    }

    @Test
    void truncates_long_failure_entry_to_max_chars() {
        String longFailure = "A".repeat(RetryContextBuilder.MAX_CHARS_PER_FAILURE + 50);
        ValidationReport report = new ValidationReport("p1", false, List.of(longFailure), "1 issue");

        String result = builder.build(report);

        // The included entry must not exceed MAX_CHARS_PER_FAILURE + ellipsis
        String bodyLine = result.lines()
                .filter(l -> l.matches("^1\\..*"))
                .findFirst()
                .orElse("");
        // bodyLine = "1. <truncated>…"
        assertThat(bodyLine.length()).isLessThan(RetryContextBuilder.MAX_CHARS_PER_FAILURE + 10);
    }

    @Test
    void total_output_stays_within_token_budget() {
        List<String> failures = Collections.nCopies(20,
                "com.example.ServiceFoo: google.cloud.pubsub import was not removed after migration");
        ValidationReport report = new ValidationReport("p1", false, failures, "20 issues");

        String result = builder.build(report);

        int tokens = builder.countTokens(result);
        assertThat(tokens).isLessThanOrEqualTo(RetryContextBuilder.TOKEN_BUDGET);
    }

    @Test
    void output_is_deterministic_for_same_input() {
        List<String> failures = List.of("X.java: issue A", "Y.java: issue B");
        ValidationReport report = new ValidationReport("p1", false, failures, "summary");

        assertThat(builder.build(report)).isEqualTo(builder.build(report));
    }
}
