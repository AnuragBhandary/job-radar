package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.LedgerFormatter;
import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.bench.BenchmarkPostings;
import com.anuragbhandary.jobradar.bench.ExtractionPrompt;
import com.anuragbhandary.jobradar.bench.ModelCall;
import com.anuragbhandary.jobradar.bench.OllamaClient;
import com.anuragbhandary.jobradar.bench.RequirementParser;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code ledger} - what a posting asks for, and what the resume can honestly say
 * against each requirement.
 *
 * <p>Read-only. It does not touch the tailored resume, the database or any
 * application; it prints the ledger and writes it as JSON under build/reports.
 *
 * <p>By default the requirements come from the deterministic reader. With
 * {@code --model} they come from a local model instead - and only the grounded
 * ones: any requirement whose quote is not in the posting is dropped by the
 * parser before the ledger sees it.
 */
@Component
public class LedgerCommand {

    private final PostingRepository postings;
    private final CoverageAnalyzer analyzer;
    private final ResumeSources sources;
    private final ObjectMapper json;

    public LedgerCommand(PostingRepository postings, CoverageAnalyzer analyzer,
            ResumeSources sources, ObjectMapper json) {
        this.postings = postings;
        this.analyzer = analyzer;
        this.sources = sources;
        this.json = json;
    }

    public void run(Map<String, String> options) {
        if (options.containsKey("sources")) {
            printSources();
            return;
        }
        List<Long> ids = new ArrayList<>();
        if (options.containsKey("posting-id")) {
            ids.add(Long.parseLong(options.get("posting-id").strip()));
        } else if (options.containsKey("posting-ids")) {
            for (String id : options.get("posting-ids").split(",")) {
                if (!id.isBlank()) {
                    ids.add(Long.parseLong(id.strip()));
                }
            }
        } else if (options.containsKey("bench")) {
            ids.addAll(BenchmarkPostings.DEFAULT.keySet());
        } else {
            System.out.println("Usage: ledger --posting-id=N | --posting-ids=a,b | --bench "
                    + "[--model=NAME] [--verbose] | --sources");
            return;
        }
        if (sources.all().isEmpty()) {
            System.out.println("No resume is configured (job-radar.resume in applicant.yml), "
                    + "so there is no evidence to cite.");
        }

        // Same "name@N" form as bench-llm, so a model that needs a GPU-layer cap
        // to run on this machine can be used here too.
        BenchLlmCommand.ModelSpec spec = options.containsKey("model")
                ? BenchLlmCommand.ModelSpec.parse(options.get("model")) : null;
        String model = spec == null ? null : spec.name();
        OllamaClient client = spec == null ? null : new OllamaClient(
                options.getOrDefault("url", BenchLlmCommand.ollamaUrl()),
                Duration.ofSeconds(BenchLlmCommand.intOption(options, "timeout-seconds", 600)),
                BenchLlmCommand.intOption(options, "num-ctx", 8192),
                BenchLlmCommand.intOption(options, "max-output-tokens", 4096), json,
                spec.asLayers());
        boolean verbose = options.containsKey("verbose");

        List<CoverageLedger> ledgers = new ArrayList<>();
        for (Long id : ids) {
            Optional<Posting> posting = postings.findById(id);
            if (posting.isEmpty()) {
                System.out.println("No posting " + id);
                continue;
            }
            Optional<CoverageLedger> ledger = client == null
                    ? Optional.of(analyzer.analyse(posting.get()))
                    : fromModel(client, model, posting.get());
            ledger.ifPresent(l -> {
                ledgers.add(l);
                System.out.print(LedgerFormatter.format(l, verbose));
            });
        }
        write(ledgers, Path.of(options.getOrDefault("out", "build/reports")));
    }

    private Optional<CoverageLedger> fromModel(OllamaClient client, String model, Posting posting) {
        ModelCall call = client.chat(model, ExtractionPrompt.system(),
                ExtractionPrompt.user(posting.getTitle(), posting.getDescriptionText()),
                ExtractionPrompt.schema());
        if (!call.ok()) {
            System.out.println("Posting " + posting.getId() + ": " + model + " failed - " + call.error());
            return Optional.empty();
        }
        RequirementParser.Result parsed = RequirementParser.parse(call.content(),
                PostingRequirements.groundingText(posting.getTitle(), posting.getDescriptionText()));
        System.out.printf("%nPosting %d: %s proposed %d, accepted %d, rejected %d "
                        + "(%d ungrounded)%n", posting.getId(), model, parsed.proposed(),
                parsed.accepted().size(), parsed.rejected().size(),
                parsed.count(RequirementParser.Reason.UNGROUNDED));
        return Optional.of(analyzer.analyse(posting.getId(), posting.getTitle(), model,
                parsed.accepted()));
    }

    private void printSources() {
        System.out.printf("%n%-48s %-15s %s%n", "id", "kind", "text");
        for (ResumeSources.SourceItem item : sources.all()) {
            System.out.printf("%-48s %-15s %s%n", item.id(), item.kind(),
                    shorten(item.text(), 70));
        }
    }

    private void write(List<CoverageLedger> ledgers, Path out) {
        if (ledgers.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(out);
            Path file = out.resolve("coverage-ledger.json");
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(ledgers));
            System.out.println("\nWrote " + file.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("Could not write the ledger: " + e.getMessage());
        }
    }

    private static String shorten(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
