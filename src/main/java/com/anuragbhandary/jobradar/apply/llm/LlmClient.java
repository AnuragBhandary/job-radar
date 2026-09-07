package com.anuragbhandary.jobradar.apply.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A chat-completions call, and nothing else.
 *
 * <p>Returns {@link Optional#empty()} on every failure rather than throwing: a
 * model being unreachable must degrade the cover letter to its template, not stop
 * an application. The one thing in this pipeline that depends on a third party
 * being up is also the one thing that is optional.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final LlmProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public LlmClient(LlmProperties config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, config.timeoutSeconds())))
                .build();
    }

    public boolean isUsable() {
        return config.isUsable();
    }

    /**
     * @param system the role and the rules, including what may not be claimed
     * @param user   the posting and the applicant's own material
     */
    public Optional<String> complete(String system, String user) {
        if (!config.isUsable()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("model", config.model());
            payload.put("temperature", config.temperature());
            payload.put("max_tokens", config.maxOutputTokens());
            payload.put("messages", java.util.List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)));
            if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
                payload.put("reasoning_effort", config.reasoningEffort());
            }
            String body = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(config.baseUrl().replaceAll("/+$", "")
                                    + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(10, config.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // Deliberately not logging the body: a 401 from some gateways
                // echoes the Authorization header back.
                log.warn("Model returned HTTP {} - falling back to the template",
                        response.statusCode());
                return Optional.empty();
            }

            JsonNode choice = mapper.readTree(response.body()).path("choices").path(0);
            String text = choice.path("message").path("content").asText("").trim();

            if (text.isEmpty()) {
                // A 200 with nothing in it. On Gemini this means the thinking
                // tokens consumed max_tokens before a word was written, and the
                // finish_reason is the only thing that says so.
                log.warn("Model returned an empty message (finish_reason: {}). "
                        + "If this is Gemini, raise max-output-tokens or set "
                        + "reasoning-effort: none.",
                        choice.path("finish_reason").asText("unknown"));
                return Optional.empty();
            }
            return Optional.of(text);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Model call failed ({}) - falling back to the template", e.getMessage());
            return Optional.empty();
        }
    }
}
