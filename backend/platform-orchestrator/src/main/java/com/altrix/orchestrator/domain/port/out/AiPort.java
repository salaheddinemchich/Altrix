package com.altrix.orchestrator.domain.port.out;

/**
 * Secondary port — abstraction over any AI provider.
 *
 * Two methods allow using a smaller/faster model for lightweight tasks
 * and a larger model for complex code rewriting.
 */
public interface AiPort {

    /** Call with the main (powerful) model — use for code generation. */
    String chat(String systemPrompt, String userContent);

    /** Call with the fast (smaller) model — use for analysis/classification. */
    String chatFast(String systemPrompt, String userContent);
}
