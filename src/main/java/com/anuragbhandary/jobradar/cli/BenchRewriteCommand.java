package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.form.PdfWriter;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.bench.OllamaClient;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark;
import com.anuragbhandary.jobradar.bench.RewriteBenchmarkReport;
import com.anuragbhandary.jobradar.bench.RewriteComparison;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code bench-rewrite} - the shadow benchmark for generative bullet rewriting.
 *
 * <p>Reads a handful of postings, builds the deterministic resume for each, asks a
 * local model to reword the selected bullets one at a time, validates every
 * rewrite in Java, and writes the two versions side by side. It changes nothing:
 * no application, no attempt row, no resume the apply flow will use. Summaries are
 * never rewritten.
 *
 * <p>Run on an otherwise idle machine. Requests are strictly sequential, the model
 * is unloaded as soon as the last rewrite returns, and the PDFs are rendered only
 * after that - so Chromium and the model never compete for memory.
 *
 * <p>With {@code --baseline=<previous report json>} (default: the first Phase 2
 * run, when present) the report compares the two runs.
 */
@Component
public class BenchRewriteCommand {

    static final Map<Long, String> DEFAULT_POSTINGS;

    static {
        Map<Long, String> set = new LinkedHashMap<>();
        set.put(779L, "Java/Spring: Java, Spring Boot and Hibernate required, all DIRECT");
        set.put(9039L, "Python/backend: Python and backend required, an explicit preferred section");
        set.put(8945L, "Distributed/backend: Kafka, event-driven, PostgreSQL, Redis required; Go and Rust NONE");
        set.put(2970L, "AI/backend: LLM, Python and async required; RAG NONE, OpenAI and Anthropic TRANSFERABLE");
        set.put(8947L, "Unfamiliar: ClickHouse operations with Kubernetes and Terraform required but only ADJACENT");
        DEFAULT_POSTINGS = Collections.unmodifiableMap(set);
    }

    static final String DEFAULT_MODEL = "gemma4:26b@24";
    static final String DEFAULT_BASELINE = "build/reports/phase2-run1/resume-rewrite-benchmark.json";

    private final PostingRepository postings;
    private final ResumeTailor tailor;
    private final CoverageAnalyzer analyzer;
    private final ResumeSources sources;
    private final ResumeRenderer renderer;
    private final PdfWriter pdf;
    private final ResumeModel resume;
    private final ObjectMapper json;

    public BenchRewriteCommand(PostingRepository postings, ResumeTailor tailor,
            CoverageAnalyzer analyzer, ResumeSources sources, ResumeRenderer renderer,
            PdfWriter pdf, ResumeModel resume, ObjectMapper json) {
        this.postings = postings;
        this.tailor = tailor;
        this.analyzer = analyzer;
        this.sources = sources;
        this.renderer = renderer;
        this.pdf = pdf;
        this.resume = resume;
        this.json = json;
    }

    public void run(Map<String, String> options) {
        BenchLlmCommand.ModelSpec spec = BenchLlmCommand.ModelSpec.parse(
                options.getOrDefault("model", DEFAULT_MODEL));
        String url = options.getOrDefault("url", BenchLlmCommand.ollamaUrl());
        Path out = Path.of(options.getOrDefault("out", "build/reports"));
        double temperature = Double.parseDouble(options.getOrDefault("temperature", "0.2"));
        Path baseline = Path.of(options.getOrDefault("baseline", DEFAULT_BASELINE));

        List<ResumeRewriteBenchmark.Case> cases = loadCases(options);
        if (cases.isEmpty()) {
            System.out.println("No postings to run.");
            return;
        }
        // Same context size and GPU layers for both, so moving between extraction
        // and rewriting never reloads the model.
        OllamaClient extractor = new OllamaClient(url, Duration.ofSeconds(600), 8192, 4096, json,
                spec.asLayers(), 0.0);
        OllamaClient writer = new OllamaClient(url, Duration.ofSeconds(300), 8192, 600, json,
                spec.asLayers(), temperature);
        Optional<String> version = writer.version();
        if (version.isEmpty()) {
            System.out.println("No Ollama server answered at " + url + ".");
            return;
        }
        if (!writer.installed().contains(spec.name())) {
            System.out.println(spec.name() + " is not installed on this Ollama server.");
            return;
        }
        System.out.printf("Ollama %s · %s (GPU layers %s) · temperature %.1f · %d postings%n",
                version.get(), spec.name(), spec.gpuLayers() == null ? "Ollama's choice"
                        : spec.gpuLayers(), temperature, cases.size());

        long started = System.nanoTime();
        List<ResumeRewriteBenchmark.PostingRun> runs = new ResumeRewriteBenchmark(
                extractor, writer, spec.name(), tailor, analyzer, sources, System.out::println)
                .run(cases);
        long modelMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("Model unloaded. Rendering both versions of each resume.");

        List<RewriteBenchmarkReport.Posting> reported = new ArrayList<>();
        for (ResumeRewriteBenchmark.PostingRun run : runs) {
            Path dir = out.resolve("resume-rewrite").resolve(String.valueOf(run.postingId()));
            reported.add(new RewriteBenchmarkReport.Posting(run,
                    render(run.deterministic(), dir, "deterministic"),
                    render(run.generative(), dir, "generative")));
        }
        long totalMillis = (System.nanoTime() - started) / 1_000_000;

        RewriteComparison comparison = null;
        if (Files.exists(baseline)) {
            try {
                comparison = RewriteComparison.of(json.readTree(baseline.toFile()), runs, sources);
            } catch (IOException e) {
                System.out.println("Could not read the baseline " + baseline + ": " + e.getMessage());
            }
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("ollama", version.get());
        settings.put("gpuLayers", spec.gpuLayers() == null ? "Ollama's choice" : spec.gpuLayers());
        settings.put("rewriteTemperature", temperature);
        settings.put("extractionTemperature", 0.0);
        settings.put("numCtx", 8192);
        settings.put("sequential", true);
        settings.put("sourceIdSchema", "enum of the one allowed id");
        settings.put("baseline", comparison == null ? "none" : baseline.toString());
        RewriteBenchmarkReport report = new RewriteBenchmarkReport(Instant.now(), spec.name(),
                settings, totalMillis, modelMillis, reported, comparison);
        try {
            Files.createDirectories(out);
            Path jsonFile = out.resolve("resume-rewrite-benchmark.json");
            Path mdFile = out.resolve("resume-rewrite-benchmark.md");
            Files.writeString(jsonFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            String markdown = report.markdown();
            Files.writeString(mdFile, markdown);
            int end = markdown.indexOf("\n---\n");
            System.out.println(end > 0 ? markdown.substring(0, end) : markdown);
            System.out.println("Wrote " + jsonFile.toAbsolutePath() + " and " + mdFile.getFileName());
        } catch (IOException e) {
            System.out.println("Could not write the report: " + e.getMessage());
        }
    }

    private List<ResumeRewriteBenchmark.Case> loadCases(Map<String, String> options) {
        Map<Long, String> wanted = new LinkedHashMap<>();
        if (options.containsKey("posting-ids")) {
            for (String id : options.get("posting-ids").split(",")) {
                if (!id.isBlank()) {
                    Long key = Long.parseLong(id.strip());
                    wanted.put(key, DEFAULT_POSTINGS.getOrDefault(key, "chosen on the command line"));
                }
            }
        } else {
            wanted.putAll(DEFAULT_POSTINGS);
        }
        List<ResumeRewriteBenchmark.Case> cases = new ArrayList<>();
        wanted.forEach((id, why) -> {
            Optional<Posting> posting = postings.findById(id);
            if (posting.isEmpty() || posting.get().getDescriptionText() == null) {
                System.out.println("Posting " + id + " is missing or has no text - skipped");
            } else {
                cases.add(new ResumeRewriteBenchmark.Case(posting.get(), why));
            }
        });
        return cases;
    }

    /** Renders with the production renderer and PDF writer, unchanged, and counts pages. */
    private Integer render(TailoredResume tailored, Path dir, String name) {
        try {
            Files.createDirectories(dir);
            String html = renderer.toHtml(tailored, resume.headline());
            Files.writeString(dir.resolve(name + ".html"), html);
            Path file = dir.resolve(name + ".pdf").toAbsolutePath();
            pdf.write(html, file);
            return pageCount(file);
        } catch (Exception e) {
            System.out.println("Could not render " + name + " for " + dir.getFileName() + ": "
                    + e.getMessage());
            return null;
        }
    }

    private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?![a-zA-Z])");

    static Integer pageCount(Path pdfFile) throws IOException {
        String raw = new String(Files.readAllBytes(pdfFile), StandardCharsets.ISO_8859_1);
        Matcher matcher = PAGE.matcher(raw);
        int pages = 0;
        while (matcher.find()) {
            pages++;
        }
        return pages == 0 ? null : pages;
    }
}
