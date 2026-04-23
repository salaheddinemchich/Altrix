package com.migrator.orchestrator.domain.port.out;

/**
 * Secondary port — abstraction over any AI provider.
 *
 * <p>The domain never imports WebClient, Groq, or OpenAI SDK.
 * Swap providers by writing a new adapter that implements this interface.
 */
public interface AiPort {

    /**
     * Sends a prompt to the AI and returns the raw text response.
     *
     * @param systemPrompt instructions that define the agent's role
     * @param userContent  the actual content for the AI to analyse
     * @return raw AI response text
     */
    String chat(String systemPrompt, String userContent);
}
