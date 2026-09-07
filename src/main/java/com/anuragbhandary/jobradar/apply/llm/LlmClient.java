package com.anuragbhandary.jobradar.apply.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
     * Why the last call failed, in words a person can act on.
     *
     * <p>Every failure used to surface as "check the key", which is right for a
     * 401 and actively misleading for a 429 - the free tier's per-minute limit is
     * the failure you actually meet, and the fix is to wait rather than to go
     * hunting through secrets.yml.
     */
    public String lastFailure() {
        return lastFailure;
    }

    private volatile String lastFailure;

    private static String describe(int status) {
        return switch (status) {
            case 429 -> "rate limited by the free tier. Wait a minute and ask again.";
            case 401, 403 -> "the API key was rejected. Check job-radar.llm.api-key.";
            case 404 -> "that model name does not exist for this endpoint.";
            case 400 -> "the request was rejected. The model may not support tool calling.";
            default -> "the model returned HTTP " + status + ".";
        };
    }

    /**
     * @param system the role and the rules, including what may not be claimed
     * @param user   the posting and the applicant's own material
     */
    /**
     * One turn of a tool-calling conversation.
     *
     * <p>Returns empty on any failure, like {@link #complete}: an unreachable
     * model should degrade the chat to an apology, not throw through a controller.
     *
     * @param tools may be empty, in which case this is a plain completion that
     *              happens to carry conversation history
     */
    public Optional<ChatTurn.Reply> chat(
            List<ChatTurn.Message> conversation, List<ChatTurn.Tool> tools) {

        if (!config.isUsable()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("model", config.model());
            payload.put("temperature", config.temperature());
            payload.put("max_tokens", config.maxOutputTokens());
            payload.put("messages", conversation.stream().map(LlmClient::toWire).toList());
            if (tools != null && !tools.isEmpty()) {
                payload.put("tools", tools.stream().map(tool -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.parameters()))).toList());
                payload.put("tool_choice", "auto");
            }
            if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
                payload.put("reasoning_effort", config.reasoningEffort());
            }

            HttpResponse<String> response = send(mapper.writeValueAsString(payload));
            if (response.statusCode() != 200) {
                lastFailure = describe(response.statusCode());
                log.warn("Model returned HTTP {} on a chat turn - {}",
                        response.statusCode(), lastFailure);
                return Optional.empty();
            }
            lastFailure = null;

            JsonNode message = mapper.readTree(response.body())
                    .path("choices").path(0).path("message");

            List<ChatTurn.ToolCall> calls = new java.util.ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                calls.add(new ChatTurn.ToolCall(
                        call.path("id").asText(""),
                        call.path("function").path("name").asText(""),
                        call.path("function").path("arguments").asText("{}")));
            }
            return Optional.of(new ChatTurn.Reply(
                    message.path("content").asText(""), calls));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Chat turn failed ({})", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A message in the wire shape.
     *
     * <p>Built by hand rather than with Jackson annotations because the shape is
     * conditional: a tool result carries {@code tool_call_id} and no tool calls,
     * an assistant turn may carry tool calls and a null content, and sending the
     * absent fields as nulls is rejected by some gateways.
     */
    private static Map<String, Object> toWire(ChatTurn.Message message) {
        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("role", message.role());
        wire.put("content", message.content() == null ? "" : message.content());
        if (message.toolCallId() != null) {
            wire.put("tool_call_id", message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", call.argumentsJson()))).toList());
        }
        return wire;
    }

    private HttpResponse<String> send(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(10, config.timeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

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
