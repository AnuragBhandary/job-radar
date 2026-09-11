package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Runs every model over every posting on the same extraction task and scores what
 * came back.
 *
 * <p>An experiment. Nothing it produces is read by any other part of the tool: it
 * writes a report, and a person decides what to do with it.
 *
 * <h2>What is measured, and what is not</h2>
 * Schema validity, grounding, failures and latency are measured directly. Token
 * counts, generation time and resident memory are what Ollama reports, or absent.
 * "Vocabulary recall" compares the model's terms with the technologies the
 * deterministic reader finds - a relative signal for whether a model is missing
 * the obvious, not a ground truth. Nothing is estimated to fill a gap.
 */
public class LocalLlmBenchmark {

    /** One posting to extract from. */
    public record Case(Long postingId, String label, String title, String countryCode,
            String description) {

        public String groundingText() {
            return PostingRequirements.groundingText(title, description);
        }
    }

    /**
     * @param evalNanos generation time as the server reported it, for tokens per
     *                  second; null when it reported none
     */
    public record CaseResult(
            Long postingId,
            String label,
            boolean ok,
            String error,
            long latencyMillis,
            Integer promptTokens,
            Integer outputTokens,
            Long evalNanos,
            boolean truncated,
            boolean schemaValid,
            int proposed,
            int withQuote,
            int grounded,
            int accepted,
            int ungrounded,
            int missingQuote,
            int malformed,
            int duplicates,
            int required,
            int preferred,
            int signal,
            Double vocabularyRecall,
            List<String> acceptedTerms,
            List<RequirementParser.Rejection> rejections) {
    }

    /**
     * @param schemaValidRate   over calls that returned anything; failures are
     *                          counted in {@code failureRate} instead
     * @param groundedQuoteRate grounded quotes over quotes given
     */
    public record Summary(
            int calls,
            int failures,
            double failureRate,
            Double schemaValidRate,
            Double groundedQuoteRate,
            int proposed,
            int accepted,
            int ungrounded,
            int missingQuote,
            int malformed,
            int duplicates,
            int truncated,
            Double avgLatencyMillis,
            Long medianLatencyMillis,
            Long maxLatencyMillis,
            Double avgPromptTokens,
            Double avgOutputTokens,
            Double outputTokensPerSecond,
            Double avgAcceptedPerPosting,
            Double avgVocabularyRecall) {
    }

    /**
     * @param loadMillis the warm-up call's round trip - mostly loading the model
     *                   from disk - and excluded from the latency averages
     * @param residency  memory the loaded model occupied, as the server reported
     */
    public record ModelResult(
            String model,
            boolean available,
            String skippedReason,
            Long loadMillis,
            LocalModel.Residency residency,
            Summary summary,
            List<CaseResult> cases) {
    }

    private final LocalModel server;
    private final Consumer<String> progress;
    private final boolean constrained;

    /**
     * @param constrained pass the JSON schema as Ollama's {@code format}. Off
     *                    measures whether a model follows the schema unaided.
     */
    public LocalLlmBenchmark(LocalModel server, Consumer<String> progress, boolean constrained) {
        this.server = server;
        this.progress = progress;
        this.constrained = constrained;
    }

    public List<ModelResult> run(List<String> models, List<Case> cases) {
        List<String> installed = server.installed();
        List<ModelResult> results = new ArrayList<>();
        for (String model : models) {
            if (!installed.isEmpty() && !isInstalled(installed, model)) {
                progress.accept(model + ": not installed - skipped");
                results.add(new ModelResult(model, false, "not installed on this server",
                        null, null, null, List.of()));
                continue;
            }
            results.add(runModel(model, cases));
        }
        return results;
    }

    private ModelResult runModel(String model, List<Case> cases) {
        progress.accept(model + ": loading");
        ModelCall warm = server.chat(model, ExtractionPrompt.system(),
                ExtractionPrompt.user("Warm-up", "Nothing is required."), format());
        if (!warm.ok()) {
            progress.accept(model + ": warm-up failed - " + warm.error());
            server.unload(model);
            return new ModelResult(model, false, "warm-up failed: " + warm.error(),
                    warm.wallMillis(), null, null, List.of());
        }

        List<CaseResult> results = new ArrayList<>();
        LocalModel.Residency residency = null;
        int n = 0;
        for (Case posting : cases) {
            n++;
            ModelCall call = server.chat(model, ExtractionPrompt.system(),
                    ExtractionPrompt.user(posting.title(), posting.description()), format());
            CaseResult result = score(posting, call);
            results.add(result);
            if (residency == null) {
                // Read while the model is loaded with a real prompt's context,
                // which is the footprint that matters.
                residency = server.residency(model).orElse(null);
            }
            progress.accept(String.format("%s: %d/%d posting %d %s", model, n, cases.size(),
                    posting.postingId(), result.ok()
                            ? String.format("%.1fs, %d accepted, %d ungrounded%s",
                                    result.latencyMillis() / 1000.0, result.accepted(),
                                    result.ungrounded(),
                                    result.schemaValid() ? "" : ", schema-invalid")
                            : "FAILED " + result.error()));
        }
        server.unload(model);
        return new ModelResult(model, true, null, warm.wallMillis(), residency,
                summarise(results), List.copyOf(results));
    }

    private Map<String, Object> format() {
        return constrained ? ExtractionPrompt.schema() : null;
    }

    static CaseResult score(Case posting, ModelCall call) {
        if (!call.ok()) {
            return new CaseResult(posting.postingId(), posting.label(), false, call.error(),
                    call.wallMillis(), null, null, null, false, false, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, null, List.of(), List.of());
        }
        RequirementParser.Result parsed =
                RequirementParser.parse(call.content(), posting.groundingText());
        List<Requirement> accepted = parsed.accepted();
        return new CaseResult(posting.postingId(), posting.label(), true, parsed.error(),
                call.wallMillis(), call.promptTokens(), call.outputTokens(), call.evalNanos(),
                call.truncated(), parsed.schemaValid(), parsed.proposed(), parsed.withQuote(),
                parsed.grounded(), accepted.size(),
                (int) parsed.count(RequirementParser.Reason.UNGROUNDED),
                (int) parsed.count(RequirementParser.Reason.MISSING_QUOTE),
                (int) parsed.count(RequirementParser.Reason.MALFORMED_ITEM),
                parsed.duplicates(),
                count(accepted, RequirementImportance.REQUIRED),
                count(accepted, RequirementImportance.PREFERRED),
                count(accepted, RequirementImportance.SIGNAL),
                vocabularyRecall(posting, accepted),
                accepted.stream().map(Requirement::term).toList(),
                parsed.rejected());
    }

    /**
     * Share of the technologies the deterministic reader finds that the model
     * also named. Null when the posting names none.
     */
    static Double vocabularyRecall(Case posting, List<Requirement> accepted) {
        Set<String> reference = new LinkedHashSet<>();
        for (Requirement r : PostingRequirements.extract(posting.title(), posting.description())) {
            if (PostingRequirements.isTechnology(r.term())) {
                reference.add(r.term().toLowerCase(Locale.ROOT));
            }
        }
        if (reference.isEmpty()) {
            return null;
        }
        Set<String> named = new LinkedHashSet<>();
        for (Requirement r : accepted) {
            named.add(PostingRequirements.canonicalSubject(r.term()).toLowerCase(Locale.ROOT));
            TechVocabulary.found(r.term()).forEach(term ->
                    named.add(PostingRequirements.ALIASES.getOrDefault(term, term)));
        }
        long hit = reference.stream().filter(named::contains).count();
        return (double) hit / reference.size();
    }

    static Summary summarise(List<CaseResult> cases) {
        int calls = cases.size();
        List<CaseResult> answered = cases.stream().filter(CaseResult::ok).toList();
        int failures = calls - answered.size();
        int withQuote = sum(answered, CaseResult::withQuote);
        int grounded = sum(answered, CaseResult::grounded);

        List<Long> latencies = answered.stream().map(CaseResult::latencyMillis).sorted().toList();
        List<Integer> prompt = answered.stream().map(CaseResult::promptTokens)
                .filter(Objects::nonNull).toList();
        List<Integer> output = answered.stream().map(CaseResult::outputTokens)
                .filter(Objects::nonNull).toList();
        List<Double> recall = answered.stream().map(CaseResult::vocabularyRecall)
                .filter(Objects::nonNull).toList();

        return new Summary(
                calls,
                failures,
                calls == 0 ? 0 : (double) failures / calls,
                answered.isEmpty() ? null
                        : (double) answered.stream().filter(CaseResult::schemaValid).count()
                                / answered.size(),
                withQuote == 0 ? null : (double) grounded / withQuote,
                sum(answered, CaseResult::proposed),
                sum(answered, CaseResult::accepted),
                sum(answered, CaseResult::ungrounded),
                sum(answered, CaseResult::missingQuote),
                sum(answered, CaseResult::malformed),
                sum(answered, CaseResult::duplicates),
                (int) answered.stream().filter(CaseResult::truncated).count(),
                latencies.isEmpty() ? null
                        : latencies.stream().mapToLong(Long::longValue).average().orElse(0),
                latencies.isEmpty() ? null : latencies.get(latencies.size() / 2),
                latencies.isEmpty() ? null : latencies.getLast(),
                prompt.isEmpty() ? null
                        : prompt.stream().mapToInt(Integer::intValue).average().orElse(0),
                output.isEmpty() ? null
                        : output.stream().mapToInt(Integer::intValue).average().orElse(0),
                tokensPerSecond(answered),
                answered.isEmpty() ? null
                        : (double) sum(answered, CaseResult::accepted) / answered.size(),
                recall.isEmpty() ? null
                        : recall.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    /** Output tokens per second of generation, from the server's own timings. */
    static Double tokensPerSecond(List<CaseResult> cases) {
        long tokens = 0;
        long nanos = 0;
        for (CaseResult result : cases) {
            if (result.outputTokens() != null && result.evalNanos() != null
                    && result.evalNanos() > 0) {
                tokens += result.outputTokens();
                nanos += result.evalNanos();
            }
        }
        return nanos == 0 ? null : tokens / (nanos / 1e9);
    }

    private static boolean isInstalled(List<String> installed, String model) {
        return installed.contains(model)
                || (!model.contains(":") && installed.contains(model + ":latest"));
    }

    private static int count(List<Requirement> requirements, RequirementImportance importance) {
        return (int) requirements.stream().filter(r -> r.importance() == importance).count();
    }

    private static int sum(List<CaseResult> cases, ToIntFunction<CaseResult> field) {
        return cases.stream().mapToInt(field).sum();
    }
}
