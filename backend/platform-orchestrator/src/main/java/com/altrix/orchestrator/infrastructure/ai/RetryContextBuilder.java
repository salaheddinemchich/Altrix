package com.altrix.orchestrator.infrastructure.ai;

import com.altrix.common.domain.model.ValidationReport;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a token-budgeted retry context string from a {@link ValidationReport} (#48).
 *
 * <p>On retry, the workflow graph prepends this string to the CoreMigratorAgent
 * system prompt so the model sees targeted failure information without exhausting
 * the input context window.
 *
 * <p>Budget strategy (total ≤ {@value #TOKEN_BUDGET} tokens):
 * <ul>
 *   <li>Include up to {@value #MAX_FAILURES} failure entries.</li>
 *   <li>Each entry is truncated to {@value #MAX_CHARS_PER_FAILURE} characters.</li>
 *   <li>A trailing "… and N more issue(s)" line is appended when failures were omitted.</li>
 * </ul>
 *
 * <p>Uses {@code cl100k_base} (GPT-4 / GPT-3.5 / Claude) token encoding via JTokkit.
 */
@Slf4j
@Component
public class RetryContextBuilder {

    public static final int TOKEN_BUDGET = 2000;
    public static final int MAX_FAILURES = 5;
    public static final int MAX_CHARS_PER_FAILURE = 200;
    public static final String HEADER = "PREVIOUS ATTEMPT FAILED. Fix these specific issues:\n\n";
    private final Encoding encoding;

    public RetryContextBuilder() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncodingForModel(ModelType.GPT_4);
    }

    /**
     * Produces the retry context string, or {@code ""} if the report is null,
     * already passed, or has no failures.
     */
    public String build(ValidationReport report) {
        if (report == null || report.passed() || report.failures().isEmpty()) {
            return "";
        }

        List<String> failures = report.failures();
        StringBuilder sb = new StringBuilder(HEADER);
        int remainingBudget = TOKEN_BUDGET - countTokens(HEADER);
        int included = 0;

        for (int i = 0; i < Math.min(failures.size(), MAX_FAILURES); i++) {
            String entry = (i + 1) + ". " + truncate(failures.get(i), MAX_CHARS_PER_FAILURE) + "\n";
            int cost = countTokens(entry);
            if (cost > remainingBudget) break;
            sb.append(entry);
            remainingBudget -= cost;
            included++;
        }

        int omitted = failures.size() - included;
        if (omitted > 0) {
            sb.append("… and ").append(omitted).append(" more issue(s).\n");
        }

        String result = sb.toString();
        log.debug("[RetryContextBuilder] {} token(s) used, {}/{} failure(s) included",
                TOKEN_BUDGET - remainingBudget, included, failures.size());
        return result;
    }

    public int countTokens(String text) {
        return encoding.countTokens(text);
    }

    private static String truncate(String s, int maxChars) {
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }
}
