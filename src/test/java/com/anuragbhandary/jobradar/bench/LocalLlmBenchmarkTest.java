package com.anuragbhandary.jobradar.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The harness against a scripted server, so failures, garbage and invented quotes
 * can be produced on demand rather than waited for.
 */
class LocalLlmBenchmarkTest {

    private static final LocalLlmBenchmark.Case JAVA = new LocalLlmBenchmark.Case(1L, "java",
            "Java Engineer", "GB", "Requirements:\n- Experience with Kafka.\n- Docker.");
    private static final LocalLlmBenchmark.Case PYTHON = new LocalLlmBenchmark.Case(2L, "python",
            "Python Engineer", "NL", "You will use FastAPI and PostgreSQL.");

    private static final String GOOD = """
            {"requirements":[{"term":"Kafka","category":"TECHNOLOGY","importance":"REQUIRED",
            "quote":"Experience with Kafka"}]}""";
    private static final String INVENTED = """
            {"requirements":[{"term":"Rust","category":"LANGUAGE","importance":"REQUIRED",
            "quote":"Five years of Rust"}]}""";

    /** Replies by the posting title in the prompt; the warm-up always succeeds. */
    private static final class Scripted implements LocalModel {
        final Map<String, ModelCall> byTitle;
        final List<String> installed;
        final List<String> unloaded = new ArrayList<>();

        Scripted(Map<String, ModelCall> byTitle, List<String> installed) {
            this.byTitle = byTitle;
            this.installed = installed;
        }

        @Override
        public ModelCall chat(String model, String system, String user, Map<String, Object> format) {
            if (model.equals("broken")) {
                return ModelCall.failed("connection refused", 3);
            }
            for (Map.Entry<String, ModelCall> entry : byTitle.entrySet()) {
                if (user.contains("TITLE: " + entry.getKey())) {
                    return entry.getValue();
                }
            }
            return ok("{\"requirements\":[]}", 100, 5, 1_000_000L);
        }

        @Override
        public List<String> installed() {
            return installed;
        }

        @Override
        public Optional<Residency> residency(String model) {
            return Optional.of(new Residency(18_000_000_000L, 16_000_000_000L));
        }

        @Override
        public void unload(String model) {
            unloaded.add(model);
        }
    }

    private static ModelCall ok(String content, long wall, int outputTokens, long evalNanos) {
        return new ModelCall(true, content, null, wall, 900, outputTokens, evalNanos, 0L, "stop");
    }

    @Test
    @DisplayName("grounded and invented requirements are counted separately")
    void scoresGroundingPerModel() {
        Scripted server = new Scripted(Map.of(
                "Java Engineer", ok(GOOD, 2_000, 100, 2_000_000_000L),
                "Python Engineer", ok(INVENTED, 4_000, 60, 1_000_000_000L)), List.of());

        LocalLlmBenchmark.ModelResult result = new LocalLlmBenchmark(server, s -> { }, true)
                .run(List.of("m"), List.of(JAVA, PYTHON)).getFirst();

        LocalLlmBenchmark.Summary summary = result.summary();
        assertThat(result.available()).isTrue();
        assertThat(summary.calls()).isEqualTo(2);
        assertThat(summary.failures()).isZero();
        assertThat(summary.schemaValidRate()).isEqualTo(1.0);
        assertThat(summary.accepted()).isEqualTo(1);
        assertThat(summary.ungrounded()).isEqualTo(1);
        assertThat(summary.groundedQuoteRate()).isEqualTo(0.5);
        assertThat(summary.avgLatencyMillis()).isEqualTo(3_000.0);
        // 160 tokens over 3 seconds of generation, as the server timed it.
        assertThat(summary.outputTokensPerSecond()).isCloseTo(53.33, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.residency().vramBytes()).isEqualTo(16_000_000_000L);
        assertThat(server.unloaded).containsExactly("m");
    }

    @Test
    @DisplayName("a call that fails counts as a failure, not as an empty answer")
    void modelFailureOnOnePosting() {
        Scripted server = new Scripted(Map.of(
                "Java Engineer", ok(GOOD, 2_000, 100, 2_000_000_000L),
                "Python Engineer", ModelCall.failed("timed out after 600s", 600_000)), List.of());

        LocalLlmBenchmark.Summary summary = new LocalLlmBenchmark(server, s -> { }, true)
                .run(List.of("m"), List.of(JAVA, PYTHON)).getFirst().summary();

        assertThat(summary.failures()).isEqualTo(1);
        assertThat(summary.failureRate()).isEqualTo(0.5);
        // Over the calls that answered - a timeout says nothing about the schema.
        assertThat(summary.schemaValidRate()).isEqualTo(1.0);
        assertThat(summary.avgLatencyMillis()).isEqualTo(2_000.0);
    }

    @Test
    @DisplayName("a model that cannot even warm up is reported as skipped with the reason")
    void unusableModel() {
        LocalLlmBenchmark.ModelResult result = new LocalLlmBenchmark(
                new Scripted(Map.of(), List.of()), s -> { }, true)
                .run(List.of("broken"), List.of(JAVA)).getFirst();

        assertThat(result.available()).isFalse();
        assertThat(result.skippedReason()).startsWith("warm-up failed").contains("connection refused");
        assertThat(result.cases()).isEmpty();
    }

    @Test
    @DisplayName("a model the server does not have is skipped, not attempted")
    void notInstalled() {
        List<LocalLlmBenchmark.ModelResult> results = new LocalLlmBenchmark(
                new Scripted(Map.of(), List.of("qwen3:14b")), s -> { }, true)
                .run(List.of("gemma4:26b", "qwen3:14b"), List.of(JAVA));

        assertThat(results.get(0).available()).isFalse();
        assertThat(results.get(0).skippedReason()).contains("not installed");
        assertThat(results.get(1).available()).isTrue();
    }

    @Test
    @DisplayName("schema-invalid replies are counted, and absent metrics stay absent")
    void invalidReplyAndMissingMetrics() {
        ModelCall noTimings = new ModelCall(true, "not json at all", null, 1_000,
                null, null, null, null, null);
        LocalLlmBenchmark.Summary summary = new LocalLlmBenchmark(
                new Scripted(Map.of("Java Engineer", noTimings), List.of()), s -> { }, false)
                .run(List.of("m"), List.of(JAVA)).getFirst().summary();

        assertThat(summary.schemaValidRate()).isEqualTo(0.0);
        assertThat(summary.groundedQuoteRate()).isNull();
        assertThat(summary.outputTokensPerSecond()).isNull();
        assertThat(summary.avgOutputTokens()).isNull();
    }

    @Test
    @DisplayName("vocabulary recall compares against what the deterministic reader finds")
    void vocabularyRecall() {
        LocalLlmBenchmark.CaseResult result = LocalLlmBenchmark.score(JAVA,
                ok(GOOD, 1_000, 10, 1L));

        // Java (the title), Kafka and Docker are in the posting; the reply named
        // only Kafka.
        assertThat(result.vocabularyRecall()).isCloseTo(1.0 / 3,
                org.assertj.core.data.Offset.offset(1e-9));
    }
}
