package com.anuragbhandary.jobradar.apply.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmClientTest {

    @Test
    @DisplayName("the wait the API asks for is read out of its own error")
    void readsRetryAfterFromTheBody() {
        // Gemini's free tier allows twenty requests a minute and one chat turn
        // with tool calling spends three or four, so a 429 is the normal failure.
        // The body says how long to wait; ignoring it made the assistant unusable
        // after a few questions.
        String body = """
                {"error":{"code":429,"message":"You exceeded your current quota.
                * Quota exceeded for metric: generate_content_free_tier_requests,
                limit: 20, model: gemini-2.5-flash
                Please retry in 38.284988294s.","status":"RESOURCE_EXHAUSTED"}}
                """;

        assertThat(LlmClient.retryAfterSeconds(body)).isEqualTo(39);
    }

    @Test
    void handlesABodyThatNamesNoDelay() {
        assertThat(LlmClient.retryAfterSeconds("{\"error\":\"nope\"}")).isEqualTo(-1);
        assertThat(LlmClient.retryAfterSeconds(null)).isEqualTo(-1);
        assertThat(LlmClient.retryAfterSeconds("")).isEqualTo(-1);
    }
}
