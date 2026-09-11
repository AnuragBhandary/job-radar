package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.bench.LocalLlmBenchmark.CaseResult;
import com.anuragbhandary.jobradar.bench.LocalLlmBenchmark.ModelResult;
import com.anuragbhandary.jobradar.bench.LocalLlmBenchmark.Summary;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything one benchmark run found, as JSON for keeping and Markdown for reading.
 *
 * <p>Absent numbers print as "n/a", never as zero: a model the server gave no
 * token count for did not produce zero tokens.
 */
public record BenchmarkReport(
        Instant generatedAt,
        Map<String, Object> environment,
        Map<String, Object> settings,
        List<PostingInfo> postings,
        List<ModelResult> models) {

    public record PostingInfo(Long id, String label, String title, String countryCode,
            int descriptionChars) {
    }

    public String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Local LLM benchmark: requirement extraction\n\n");
        md.append("Generated ").append(generatedAt).append("\n\n");
        md.append("Environment: ");
        environment.forEach((k, v) -> md.append(k).append(" `").append(v).append("` "));
        md.append("\n\nSettings: ");
        settings.forEach((k, v) -> md.append(k).append(" `").append(v).append("` "));
        md.append("\n\n");

        md.append("## Results\n\n");
        md.append("| Model | Calls | Failures | Schema-valid | Grounded quotes | Ungrounded "
                + "| Missing quote | Malformed | Accepted / posting | Vocabulary recall "
                + "| Avg latency | Median | Max | Avg output tokens | Generation tok/s "
                + "| Load | Resident (on GPU) | Truncated |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:"
                + "|---:|---:|---:|\n");
        for (ModelResult model : models) {
            if (!model.available()) {
                md.append("| ").append(model.model()).append(" | skipped: ")
                        .append(model.skippedReason()).append(" |||||||||||||||||\n");
                continue;
            }
            Summary s = model.summary();
            md.append("| ").append(model.model())
                    .append(" | ").append(s.calls())
                    .append(" | ").append(s.failures())
                    .append(" | ").append(pct(s.schemaValidRate()))
                    .append(" | ").append(pct(s.groundedQuoteRate()))
                    .append(" | ").append(s.ungrounded())
                    .append(" | ").append(s.missingQuote())
                    .append(" | ").append(s.malformed())
                    .append(" | ").append(num(s.avgAcceptedPerPosting(), 1))
                    .append(" | ").append(pct(s.avgVocabularyRecall()))
                    .append(" | ").append(secs(s.avgLatencyMillis()))
                    .append(" | ").append(secs(s.medianLatencyMillis() == null ? null
                            : s.medianLatencyMillis().doubleValue()))
                    .append(" | ").append(secs(s.maxLatencyMillis() == null ? null
                            : s.maxLatencyMillis().doubleValue()))
                    .append(" | ").append(num(s.avgOutputTokens(), 0))
                    .append(" | ").append(num(s.outputTokensPerSecond(), 1))
                    .append(" | ").append(secs(model.loadMillis() == null ? null
                            : model.loadMillis().doubleValue()))
                    .append(" | ").append(memory(model.residency()))
                    .append(" | ").append(s.truncated())
                    .append(" |\n");
        }

        md.append("\n## Per posting: accepted / ungrounded, latency\n\n| Posting |");
        models.stream().filter(ModelResult::available)
                .forEach(m -> md.append(' ').append(m.model()).append(" |"));
        md.append("\n|---|");
        models.stream().filter(ModelResult::available).forEach(m -> md.append("---|"));
        md.append('\n');
        for (PostingInfo posting : postings) {
            md.append("| ").append(posting.id()).append(" ").append(posting.label()).append(" |");
            for (ModelResult model : models) {
                if (!model.available()) {
                    continue;
                }
                CaseResult result = model.cases().stream()
                        .filter(c -> c.postingId().equals(posting.id()))
                        .findFirst().orElse(null);
                if (result == null) {
                    md.append(" n/a |");
                } else if (!result.ok()) {
                    md.append(" failed |");
                } else {
                    md.append(' ').append(result.accepted()).append(" / ")
                            .append(result.ungrounded())
                            .append(result.schemaValid() ? "" : " (invalid)")
                            .append(", ").append(secs((double) result.latencyMillis()))
                            .append(" |");
                }
            }
            md.append('\n');
        }

        md.append("\n## Ungrounded quotes (first 6 per model)\n\n");
        for (ModelResult model : models) {
            if (!model.available()) {
                continue;
            }
            List<String> examples = model.cases().stream()
                    .flatMap(c -> c.rejections().stream()
                            .filter(r -> r.reason() == RequirementParser.Reason.UNGROUNDED)
                            .map(r -> "posting " + c.postingId() + ": **" + r.term() + "** — \""
                                    + shorten(r.quote(), 140) + "\""))
                    .limit(6)
                    .toList();
            md.append("**").append(model.model()).append("**");
            if (examples.isEmpty()) {
                md.append(": none\n\n");
            } else {
                md.append("\n\n");
                examples.forEach(e -> md.append("- ").append(e).append('\n'));
                md.append('\n');
            }
        }

        md.append("""
                ## Reading this

                - **Grounded quotes** is the number that matters most: the share of quotes Java
                  found verbatim in the posting. An ungrounded quote is a requirement the model
                  attributed to the employer in words the employer did not write.
                - **Schema-valid** is over calls that returned anything. With the schema passed
                  as Ollama's `format`, invalid output mostly means a reply cut off by the
                  output-token cap (see Truncated).
                - **Vocabulary recall** compares the model's terms with the technologies the
                  deterministic reader finds. It is relative, not ground truth; a low figure
                  means the model missed technologies a regex could see.
                - **Resident (on GPU)** is Ollama's own report of the loaded model's memory. A
                  GPU share below the total means some layers ran on the CPU. It is not always
                  right: the runner's own memory (`ps` on `llama-server`) and the "offloaded
                  N/M layers" line in the Ollama log are the better sources when they disagree.
                - **Failures** include replies Ollama returned as HTTP 200 with nothing
                  generated, which is how a runner out of GPU memory shows up.
                - Latency excludes the warm-up call, which is shown as Load.
                """);
        return md.toString();
    }

    private static String pct(Double value) {
        return value == null ? "n/a" : String.format("%.1f%%", value * 100);
    }

    private static String num(Double value, int decimals) {
        return value == null ? "n/a" : String.format("%." + decimals + "f", value);
    }

    private static String secs(Double millis) {
        return millis == null ? "n/a" : String.format("%.1fs", millis / 1000);
    }

    private static String memory(LocalModel.Residency residency) {
        if (residency == null) {
            return "n/a";
        }
        return String.format("%.1f GB (%.1f GB)", residency.sizeBytes() / 1e9,
                residency.vramBytes() / 1e9);
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
