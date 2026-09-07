package com.anuragbhandary.jobradar.apply.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where to send a prompt.
 *
 * <p>One OpenAI-shaped chat-completions client rather than a provider SDK, because
 * Groq, Gemini, Together and OpenAI itself all speak that shape and the whole
 * difference between them is a base URL and a model name. A provider SDK would be
 * three dependencies to swap when a free tier changes its terms, which is a thing
 * free tiers do.
 *
 * <p>Off by default. With no key configured the cover letter falls back to a
 * template, and every other part of the tool is unaffected - nothing in the
 * screening, fetching or filling path calls a model.
 *
 * @param maxOutputTokens a cover letter is 250 words. The cap is a cost ceiling
 *                        and a guard against a model that decides to write an
 *                        essay into a 2000-character box.
 * @param reasoningEffort sent only when set. Gemini 2.5 thinks by default and
 *                        those tokens come out of {@code maxOutputTokens}, so a
 *                        low cap returns {@code finish_reason: length} with an
 *                        empty message and no error - the request succeeds and
 *                        produces nothing. "none" spends the budget on the answer.
 *                        Left blank for providers that reject the field.
 */
@ConfigurationProperties(prefix = "job-radar.llm")
public record LlmProperties(
        boolean enabled,
        String baseUrl,
        String model,
        String apiKey,
        double temperature,
        int maxOutputTokens,
        int timeoutSeconds,
        String reasoningEffort) {

    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }
}
