package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.bench.BenchmarkPostings;
import com.anuragbhandary.jobradar.bench.BenchmarkReport;
import com.anuragbhandary.jobradar.bench.LocalLlmBenchmark;
import com.anuragbhandary.jobradar.bench.OllamaClient;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code bench-llm} - compares local models on extracting requirements from real
 * postings.
 *
 * <p>Developer-only and read-only. It reads postings from the database, sends
 * them to a local Ollama server, and writes two report files; it changes nothing
 * in the database and nothing it finds is read by any other command.
 *
 * <p>Models run one at a time and are unloaded between runs, so a 24 GB machine
 * never holds two.
 */
@Component
public class BenchLlmCommand {

    /**
     * The three models compared, with the GPU-layer caps that made them run on a
     * 24 GB M5 Pro at the default GPU memory limit. Uncapped, Ollama put all of
     * gemma4:26b and 57 of qwen3.6:27b's 66 layers on the GPU, and Metal ran out
     * of memory on most requests; qwen3:14b fits whole.
     */
    static final List<String> DEFAULT_MODELS =
            List.of("gemma4:26b@24", "qwen3.6:27b@45", "qwen3:14b");

    /** "name" or "name@N", N being the number of layers to load on the GPU. */
    record ModelSpec(String name, Integer gpuLayers) {

        static ModelSpec parse(String spec) {
            String trimmed = spec.strip();
            int at = trimmed.lastIndexOf('@');
            return at > 0
                    ? new ModelSpec(trimmed.substring(0, at),
                            Integer.parseInt(trimmed.substring(at + 1)))
                    : new ModelSpec(trimmed, null);
        }

        Map<String, Integer> asLayers() {
            return gpuLayers == null ? Map.of() : Map.of(name, gpuLayers);
        }
    }

    private final PostingRepository postings;
    private final ObjectMapper json;

    public BenchLlmCommand(PostingRepository postings, ObjectMapper json) {
        this.postings = postings;
        this.json = json;
    }

    public void run(Map<String, String> options) {
        List<String> specs = options.containsKey("models")
                ? Arrays.stream(options.get("models").split(",")).map(String::strip)
                        .filter(s -> !s.isEmpty()).toList()
                : DEFAULT_MODELS;
        // "qwen3.6:27b@45" caps that model at 45 GPU layers; see OllamaClient.
        List<String> models = new ArrayList<>();
        Map<String, Integer> gpuLayers = new LinkedHashMap<>();
        for (String spec : specs) {
            ModelSpec parsed = ModelSpec.parse(spec);
            models.add(parsed.name());
            gpuLayers.putAll(parsed.asLayers());
        }
        String url = options.getOrDefault("url", ollamaUrl());
        int timeoutSeconds = intOption(options, "timeout-seconds", 600);
        int contextTokens = intOption(options, "num-ctx", 8192);
        int maxOutput = intOption(options, "max-output-tokens", 4096);
        boolean constrained = !options.containsKey("unconstrained");
        Path out = Path.of(options.getOrDefault("out", "build/reports"));

        List<LocalLlmBenchmark.Case> cases = loadCases(options);
        if (cases.isEmpty()) {
            System.out.println("No postings to benchmark.");
            return;
        }

        OllamaClient client = new OllamaClient(url, Duration.ofSeconds(timeoutSeconds),
                contextTokens, maxOutput, json, gpuLayers);
        Optional<String> version = client.version();
        if (version.isEmpty()) {
            System.out.println("No Ollama server answered at " + url
                    + ". Start one with `ollama serve`, or pass --url=...");
            return;
        }
        System.out.printf("Ollama %s at %s · %d postings · models %s · schema %s%n",
                version.get(), url, cases.size(), models,
                constrained ? "enforced" : "not enforced");

        List<LocalLlmBenchmark.ModelResult> results =
                new LocalLlmBenchmark(client, System.out::println, constrained).run(models, cases);

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("ollama", version.get());
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("arch", System.getProperty("os.arch"));
        environment.put("cpus", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            environment.put("memoryGb", Math.round(os.getTotalMemorySize() / 1e9));
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("schemaEnforced", constrained);
        settings.put("numCtx", contextTokens);
        settings.put("maxOutputTokens", maxOutput);
        settings.put("timeoutSeconds", timeoutSeconds);
        settings.put("temperature", 0);
        settings.put("thinking", "off where the model accepts the switch");
        settings.put("gpuLayers", gpuLayers.isEmpty() ? "Ollama's choice" : gpuLayers);

        BenchmarkReport report = new BenchmarkReport(Instant.now(), environment, settings,
                cases.stream().map(c -> new BenchmarkReport.PostingInfo(c.postingId(), c.label(),
                        c.title(), c.countryCode(), c.description().length())).toList(),
                results);
        try {
            Files.createDirectories(out);
            Path jsonFile = out.resolve("local-llm-benchmark.json");
            Path mdFile = out.resolve("local-llm-benchmark.md");
            Files.writeString(jsonFile, json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report));
            String markdown = report.markdown();
            Files.writeString(mdFile, markdown);
            System.out.println();
            System.out.println(markdown.substring(0, markdown.indexOf("\n## Per posting")));
            System.out.println("Wrote " + jsonFile.toAbsolutePath() + " and " + mdFile.getFileName());
        } catch (IOException e) {
            System.out.println("Could not write the report: " + e.getMessage());
        }
    }

    private List<LocalLlmBenchmark.Case> loadCases(Map<String, String> options) {
        Map<Long, String> wanted = new LinkedHashMap<>();
        if (options.containsKey("posting-ids")) {
            for (String id : options.get("posting-ids").split(",")) {
                if (!id.isBlank()) {
                    wanted.put(Long.parseLong(id.strip()), "chosen on the command line");
                }
            }
        } else {
            wanted.putAll(BenchmarkPostings.DEFAULT);
        }
        int limit = intOption(options, "limit", Integer.MAX_VALUE);

        List<LocalLlmBenchmark.Case> cases = new ArrayList<>();
        for (Map.Entry<Long, String> entry : wanted.entrySet()) {
            if (cases.size() >= limit) {
                break;
            }
            Optional<Posting> posting = postings.findById(entry.getKey());
            if (posting.isEmpty() || posting.get().getDescriptionText() == null
                    || posting.get().getDescriptionText().isBlank()) {
                System.out.println("Posting " + entry.getKey() + " is missing or has no text - skipped");
                continue;
            }
            Posting p = posting.get();
            cases.add(new LocalLlmBenchmark.Case(p.getId(), entry.getValue(), p.getTitle(),
                    p.getCountryCode(), p.getDescriptionText()));
        }
        return cases;
    }

    static String ollamaUrl() {
        String configured = System.getenv("JOB_RADAR_OLLAMA_URL");
        return configured == null || configured.isBlank() ? "http://localhost:11434" : configured;
    }

    static int intOption(Map<String, String> options, String name, int fallback) {
        try {
            return options.containsKey(name) ? Integer.parseInt(options.get(name)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
