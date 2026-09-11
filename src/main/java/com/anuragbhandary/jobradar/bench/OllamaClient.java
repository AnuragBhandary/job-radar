package com.anuragbhandary.jobradar.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ollama's native API, for the benchmark only.
 *
 * <p>Not {@link com.anuragbhandary.jobradar.apply.llm.LlmClient}, on purpose. That
 * client speaks the OpenAI-compatible endpoint, which is what the production
 * callers need, and it has no way to pass a JSON schema - Ollama's
 * OpenAI-compatible endpoint does not reliably honour {@code response_format}, and
 * the native {@code /api/chat} takes the schema as {@code format}. The native
 * response also reports token counts and load and generation time, which the
 * compatible one does not. Keeping this separate means the experiment cannot
 * change what the cover letter or the chat sends.
 *
 * <p>Thinking is switched off for every model that accepts the switch, so the
 * models are compared on the same task. A model that rejects the switch is
 * retried without it and remembered.
 */
public class OllamaClient implements LocalModel {

    private final String baseUrl;
    private final Duration timeout;
    private final int contextTokens;
    private final int maxOutputTokens;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Set<String> rejectsThinkSwitch = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> gpuLayers;
    private final double temperature;

    /**
     * @param contextTokens the context window to load the model with. Smaller
     *                      than the model's maximum on purpose: the KV cache
     *                      scales with it, and on a 24 GB machine that memory is
     *                      the difference between the model on the GPU and half
     *                      of it on the CPU.
     */
    public OllamaClient(String baseUrl, Duration timeout, int contextTokens,
            int maxOutputTokens, ObjectMapper json) {
        this(baseUrl, timeout, contextTokens, maxOutputTokens, json, Map.of());
    }

    /**
     * @param gpuLayers per model, how many layers to put on the GPU, for a model
     *                  Ollama would otherwise over-commit. Measured on this
     *                  project's target machine: qwen3.6:27b gets 57 of 66 layers
     *                  by default and then runs Metal out of memory on every
     *                  request; capped at 45 it runs. A request option, not a
     *                  system setting.
     */
    public OllamaClient(String baseUrl, Duration timeout, int contextTokens,
            int maxOutputTokens, ObjectMapper json, Map<String, Integer> gpuLayers) {
        this(baseUrl, timeout, contextTokens, maxOutputTokens, json, gpuLayers, 0.0);
    }

    /**
     * @param temperature zero for extraction, where the comparison must not depend
     *                    on a lucky sample; a little above for rewriting, where a
     *                    single fixed phrasing tends to echo the source back
     */
    public OllamaClient(String baseUrl, Duration timeout, int contextTokens,
            int maxOutputTokens, ObjectMapper json, Map<String, Integer> gpuLayers,
            double temperature) {
        this.temperature = temperature;
        this.gpuLayers = Map.copyOf(gpuLayers);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.contextTokens = contextTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public ModelCall chat(String model, String system, String user, Map<String, Object> format) {
        long started = System.nanoTime();
        try {
            boolean think = !rejectsThinkSwitch.contains(model);
            HttpResponse<String> response = post("/api/chat",
                    body(model, system, user, format, think), timeout);
            if (think && response.statusCode() == 400
                    && response.body().toLowerCase(Locale.ROOT).contains("think")) {
                rejectsThinkSwitch.add(model);
                response = post("/api/chat", body(model, system, user, format, false), timeout);
            }
            long wall = millisSince(started);
            if (response.statusCode() != 200) {
                return ModelCall.failed("HTTP " + response.statusCode() + ": "
                        + abbreviate(response.body(), 300), wall);
            }
            return fromResponse(json.readTree(response.body()), wall);
        } catch (HttpTimeoutException e) {
            return ModelCall.failed("timed out after " + timeout.toSeconds() + "s",
                    millisSince(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModelCall.failed("interrupted", millisSince(started));
        } catch (IOException | RuntimeException e) {
            return ModelCall.failed(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    millisSince(started));
        }
    }

    /**
     * A 200 from {@code /api/chat}, read into a call result.
     *
     * <p>An empty message with no generated tokens is a failure, not an answer.
     * That is what Ollama returns when the runner fails mid-request - on a 24 GB
     * machine, Metal running out of GPU memory for a model that only just fits -
     * and counting it as a schema-invalid reply blames the model for the server.
     * A reply that generated tokens and still has no content (all of it spent
     * thinking) is the model's doing, and stays a reply.
     */
    static ModelCall fromResponse(JsonNode node, long wall) {
        if (node.hasNonNull("error")) {
            return ModelCall.failed(node.get("error").asText(), wall);
        }
        String content = node.path("message").path("content").asText("");
        Integer outputTokens = intOrNull(node, "eval_count");
        if (content.isBlank() && (outputTokens == null || outputTokens == 0)) {
            return ModelCall.failed("empty reply with no tokens generated - the server failed "
                    + "silently; check the Ollama log (usually Metal out of GPU memory)", wall);
        }
        return new ModelCall(true, content, null, wall,
                intOrNull(node, "prompt_eval_count"),
                outputTokens,
                longOrNull(node, "eval_duration"),
                longOrNull(node, "load_duration"),
                node.hasNonNull("done_reason") ? node.get("done_reason").asText() : null);
    }

    private Map<String, Object> body(String model, String system, String user,
            Map<String, Object> format, boolean think) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        if (format != null) {
            body.put("format", format);
        }
        if (think) {
            body.put("think", false);
        }
        // Zero temperature: this is extraction, and the comparison should not
        // depend on which model drew the luckier sample.
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("num_ctx", contextTokens);
        options.put("num_predict", maxOutputTokens);
        if (gpuLayers.containsKey(model)) {
            options.put("num_gpu", gpuLayers.get(model));
        }
        body.put("options", options);
        body.put("keep_alive", "10m");
        return body;
    }

    @Override
    public List<String> installed() {
        try {
            HttpResponse<String> response = get("/api/tags");
            if (response.statusCode() != 200) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            json.readTree(response.body()).path("models")
                    .forEach(m -> names.add(m.path("name").asText()));
            return List.copyOf(names);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public Optional<Residency> residency(String model) {
        try {
            HttpResponse<String> response = get("/api/ps");
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            for (JsonNode loaded : json.readTree(response.body()).path("models")) {
                if (model.equals(loaded.path("name").asText())
                        || model.equals(loaded.path("model").asText())) {
                    return Optional.of(new Residency(
                            loaded.path("size").asLong(), loaded.path("size_vram").asLong()));
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void unload(String model) {
        try {
            post("/api/generate", Map.of("model", model, "keep_alive", 0),
                    Duration.ofSeconds(60));
        } catch (Exception e) {
            // Best effort. The next model loading evicts it anyway, more slowly.
        }
    }

    /** The server's version, or empty when nothing answers. */
    public Optional<String> version() {
        try {
            HttpResponse<String> response = get("/api/version");
            return response.statusCode() == 200
                    ? Optional.of(json.readTree(response.body()).path("version").asText())
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, Duration wait)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(wait)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
