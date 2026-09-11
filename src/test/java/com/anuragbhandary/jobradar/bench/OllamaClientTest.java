package com.anuragbhandary.jobradar.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading Ollama's replies - in particular the one that looks like success and is
 * not. Found in the first real run: qwen3.6:27b on a 24 GB machine ran Metal out of
 * memory on every request and Ollama answered each with HTTP 200 and nothing in it.
 */
class OllamaClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ModelCall read(String body) throws Exception {
        return OllamaClient.fromResponse(JSON.readTree(body), 1_000);
    }

    @Test
    @DisplayName("a normal reply carries its content and the server's own counts")
    void normalReply() throws Exception {
        ModelCall call = read("""
                {"message":{"role":"assistant","content":"{\\"requirements\\":[]}"},
                 "done":true,"done_reason":"stop","prompt_eval_count":355,
                 "eval_count":5,"eval_duration":250000000,"load_duration":1000}""");

        assertThat(call.ok()).isTrue();
        assertThat(call.content()).isEqualTo("{\"requirements\":[]}");
        assertThat(call.promptTokens()).isEqualTo(355);
        assertThat(call.outputTokens()).isEqualTo(5);
        assertThat(call.evalNanos()).isEqualTo(250_000_000L);
    }

    @Test
    @DisplayName("an empty 200 with no tokens generated is a server failure, not a bad reply")
    void silentServerFailure() throws Exception {
        ModelCall call = read("""
                {"model":"qwen3.6:27b","message":{"role":"assistant","content":""},
                 "done":true,"done_reason":"stop"}""");

        assertThat(call.ok()).isFalse();
        assertThat(call.error()).contains("no tokens generated").contains("GPU memory");
    }

    @Test
    @DisplayName("a reply spent entirely on thinking is the model's doing, and stays a reply")
    void thinkingOnlyReply() throws Exception {
        ModelCall call = read("""
                {"message":{"role":"assistant","content":"","thinking":"Let me consider..."},
                 "done":true,"done_reason":"length","eval_count":4096}""");

        assertThat(call.ok()).isTrue();
        assertThat(call.content()).isEmpty();
        assertThat(call.truncated()).isTrue();
    }

    @Test
    @DisplayName("an error field is a failure with the server's own words")
    void errorField() throws Exception {
        ModelCall call = read("{\"error\":\"model 'x' not found\"}");

        assertThat(call.ok()).isFalse();
        assertThat(call.error()).isEqualTo("model 'x' not found");
    }
}
